/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.SectorFramer;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.engine.logger.LoggerUtil;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.storage.messages.SessionIdDecoder;
import uk.co.real_logic.artio.storage.messages.SessionIdEncoder;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32;

import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_INITIAL_SEQUENCE_INDEX;
import static uk.co.real_logic.artio.engine.SectorFramer.*;
import static uk.co.real_logic.artio.session.SessionIdStrategy.INSUFFICIENT_SPACE;
import static uk.co.real_logic.artio.storage.messages.SessionIdEncoder.BLOCK_LENGTH;

/**
 * Identifies which sessions are currently authenticated.
 * <p>
 * The session ids table is saved into a file. Records are written out using the {@link SessionIdEncoder}
 * and aren't allowed to span sectors. Each sector has a CRC32 checksum and each checksum is updated after writing
 * each session id record.
 */
public class SessionContexts
{

    static final SessionContext DUPLICATE_SESSION = new SessionContext(
        null,
        -3,
        -3,
        Session.UNKNOWN_TIME,
        Session.UNKNOWN_TIME,
        null,
        OUT_OF_SPACE,
        DEFAULT_INITIAL_SEQUENCE_INDEX,
        null);
    static final SessionContext UNKNOWN_SESSION = new SessionContext(
        null,
        Session.UNKNOWN,
        (int)Session.UNKNOWN,
        Session.UNKNOWN_TIME,
        Session.UNKNOWN_TIME,
        null,
        OUT_OF_SPACE,
        DEFAULT_INITIAL_SEQUENCE_INDEX,
        null);
    static final long LOWEST_VALID_SESSION_ID = 1L;

    private static final int HEADER_SIZE = MessageHeaderDecoder.ENCODED_LENGTH;

    private static final int ENCODING_BUFFER_SIZE = SECTOR_SIZE - CHECKSUM_SIZE;
    private final UnsafeBuffer compositeKeyBuffer = new UnsafeBuffer(new byte[ENCODING_BUFFER_SIZE]);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final SessionIdEncoder sessionIdEncoder = new SessionIdEncoder();
    private final int actingBlockLength = sessionIdEncoder.sbeBlockLength();
    private final int actingVersion = sessionIdEncoder.sbeSchemaVersion();

    private final LongHashSet currentlyAuthenticatedSessionIds = new LongHashSet();
    private final CopyOnWriteArrayList<SessionInfo> allSessions = new CopyOnWriteArrayList<>();
    private final Map<CompositeKey, SessionContext> compositeToContext = new HashMap<>();

    private final CRC32 crc32 = new CRC32();
    private final SectorFramer sectorFramer;
    private final ByteBuffer byteBuffer;

    private final AtomicBuffer buffer;
    private final SessionIdStrategy idStrategy;
    private final ErrorHandler errorHandler;
    private final MappedFile mappedFile;
    private final int initialSequenceIndex;

    private int filePosition;
    private long counter = LOWEST_VALID_SESSION_ID;

    public SessionContexts(
        final MappedFile mappedFile,
        final SessionIdStrategy idStrategy,
        final int initialSequenceIndex,
        final ErrorHandler errorHandler)
    {
        this.mappedFile = mappedFile;
        this.buffer = mappedFile.buffer();
        this.byteBuffer = this.buffer.byteBuffer();
        sectorFramer = new SectorFramer(buffer.capacity());
        this.idStrategy = idStrategy;
        this.initialSequenceIndex = initialSequenceIndex;
        this.errorHandler = errorHandler;
        loadBuffer();
        allSessions.addAll(compositeToContext.values());
    }

    private void loadBuffer()
    {
        checkByteBuffer();
        initialiseBuffer();

        final SessionIdDecoder sessionIdDecoder = new SessionIdDecoder();

        int sectorEnd = 0;
        filePosition = HEADER_SIZE;
        final int lastRecordStart = buffer.capacity() - BLOCK_LENGTH;
        while (filePosition < lastRecordStart)
        {
            sectorEnd = validateSectorChecksum(filePosition, sectorEnd);
            long sessionId = wrap(sessionIdDecoder, filePosition);
            if (sessionId == 0)
            {
                final int nextSectorPeekPosition = sectorEnd;
                if (nextSectorPeekPosition > lastRecordStart)
                {
                    return;
                }

                sessionId = wrap(sessionIdDecoder, nextSectorPeekPosition);
                if (sessionId == 0)
                {
                    return;
                }
                else
                {
                    filePosition = nextSectorPeekPosition;
                }
            }
            final int sequenceIndex = sessionIdDecoder.sequenceIndex();
            final long lastLogonTime = sessionIdDecoder.logonTime();
            final long lastSequenceResetTime = sessionIdDecoder.lastSequenceResetTime();
            final int compositeKeyLength = sessionIdDecoder.compositeKeyLength();
            final String lastFixDictionary = sessionIdDecoder.lastFixDictionary();
            filePosition = sessionIdDecoder.limit();
            final CompositeKey compositeKey = idStrategy.load(
                buffer, filePosition, compositeKeyLength);
            if (compositeKey == null)
            {
                return;
            }

            final SessionContext sessionContext = new SessionContext(compositeKey,
                sessionId, sequenceIndex, lastLogonTime, lastSequenceResetTime, this, filePosition,
                initialSequenceIndex, FixDictionary.of(FixDictionary.find(lastFixDictionary)));
            compositeToContext.put(compositeKey, sessionContext);

            counter = Math.max(counter, sessionId + 1);

            filePosition += compositeKeyLength;
        }
    }

    private long wrap(final SessionIdDecoder sessionIdDecoder, final int nextSectorPeekPosition)
    {
        sessionIdDecoder.wrap(buffer, nextSectorPeekPosition, actingBlockLength, actingVersion);
        return sessionIdDecoder.sessionId();
    }

    private void checkByteBuffer()
    {
        if (byteBuffer == null)
        {
            throw new IllegalStateException("Must use atomic buffer backed by a byte buffer");
        }
    }

    private void initialiseBuffer()
    {
        if (LoggerUtil.initialiseBuffer(
            buffer,
            headerEncoder,
            headerDecoder,
            sessionIdEncoder.sbeSchemaId(),
            sessionIdEncoder.sbeTemplateId(),
            actingVersion,
            actingBlockLength,
            errorHandler))
        {
            updateChecksum(0, FIRST_CHECKSUM_LOCATION);
            mappedFile.force();
        }
    }

    private int validateSectorChecksum(final int position, final int sectorEnd)
    {
        if (position > sectorEnd)
        {
            final int nextSectorEnd = sectorEnd + SECTOR_SIZE;
            final int nextChecksum = nextSectorEnd - CHECKSUM_SIZE;
            crc32.reset();
            byteBuffer.clear();
            ByteBufferUtil.position(byteBuffer, sectorEnd);
            ByteBufferUtil.limit(byteBuffer, nextChecksum);
            crc32.update(byteBuffer);
            final int calculatedChecksum = (int)crc32.getValue();
            final int savedChecksum = buffer.getInt(nextChecksum);
            validateCheckSum(
                "session ids", sectorEnd, nextSectorEnd, savedChecksum, calculatedChecksum, errorHandler);
            return nextSectorEnd;
        }

        return sectorEnd;
    }

    public SessionContext onLogon(final CompositeKey compositeKey, final FixDictionary fixDictionary)
    {
        final SessionContext sessionContext = newSessionContext(compositeKey, fixDictionary);

        if (!currentlyAuthenticatedSessionIds.add(sessionContext.sessionId()))
        {
            return DUPLICATE_SESSION;
        }

        return sessionContext;
    }

    SessionContext newSessionContext(final CompositeKey compositeKey, final FixDictionary fixDictionary)
    {
        return compositeToContext.computeIfAbsent(compositeKey, key -> onNewLogon(key, fixDictionary));
    }

    private SessionContext onNewLogon(final CompositeKey compositeKey, final FixDictionary fixDictionary)
    {
        final long sessionId = counter++;
        final SessionContext sessionContext = assignSessionId(
            compositeKey,
            sessionId,
            SessionInfo.UNKNOWN_SEQUENCE_INDEX,
            fixDictionary);
        allSessions.add(sessionContext);
        return sessionContext;
    }

    private SessionContext assignSessionId(
        final CompositeKey compositeKey,
        final long sessionId,
        final int sequenceIndex,
        final FixDictionary fixDictionary)
    {
        final String fixDictionaryName = fixDictionary.getClass().getName();
        int keyPosition = OUT_OF_SPACE;
        final int compositeKeyLength = idStrategy.save(compositeKey, compositeKeyBuffer, 0);
        if (compositeKeyLength == INSUFFICIENT_SPACE)
        {
            errorHandler.onError(new IllegalStateException(String.format(
                "Unable to save record session id %d for %s, because the buffer is too small",
                sessionId,
                compositeKey)));
            return new SessionContext(
                compositeKey,
                sessionId,
                sequenceIndex,
                Session.UNKNOWN_TIME,
                Session.UNKNOWN_TIME,
                this,
                OUT_OF_SPACE,
                initialSequenceIndex,
                fixDictionary);
        }
        else
        {
            if (filePosition != OUT_OF_SPACE)
            {
                filePosition = sectorFramer.claim(filePosition, BLOCK_LENGTH + compositeKeyLength);
                keyPosition = filePosition;
                if (filePosition == OUT_OF_SPACE)
                {
                    errorHandler.onError(new IllegalStateException(
                        "Run out of space when storing: " + compositeKey));
                }
                else
                {
                    sessionIdEncoder
                        .wrap(buffer, filePosition)
                        .sessionId(sessionId)
                        .sequenceIndex(sequenceIndex)
                        .logonTime(Session.UNKNOWN_TIME)
                        .compositeKeyLength(compositeKeyLength)
                        .lastFixDictionary(fixDictionaryName);
                    filePosition = sessionIdEncoder.limit();

                    buffer.putBytes(filePosition, compositeKeyBuffer, 0, compositeKeyLength);
                    filePosition += compositeKeyLength;

                    updateChecksum(sectorFramer.sectorStart(), sectorFramer.checksumOffset());
                    mappedFile.force();
                }
            }

            return new SessionContext(
                compositeKey, sessionId,
                sequenceIndex,
                Session.UNKNOWN_TIME,
                Session.UNKNOWN_TIME,
                this,
                keyPosition,
                initialSequenceIndex,
                fixDictionary);
        }
    }

    void sequenceReset(final long sessionId, final long resetTime)
    {
        final Entry<CompositeKey, SessionContext> entry = lookupById(sessionId);
        if (entry != null)
        {
            final SessionContext context = entry.getValue();
            context.onSequenceReset(resetTime);
        }
    }

    Entry<CompositeKey, SessionContext> lookupById(final long sessionId)
    {
        for (final Entry<CompositeKey, SessionContext> entry : compositeToContext.entrySet())
        {
            if (entry.getValue().sessionId() == sessionId)
            {
                return entry;
            }
        }

        return null;
    }

    // TODO: optimisation, more efficient checksumming, only checksum new data
    private void updateChecksum(final int start, final int checksumOffset)
    {
        final int endOfData = checksumOffset;
        byteBuffer.clear();
        ByteBufferUtil.position(byteBuffer, start);
        ByteBufferUtil.limit(byteBuffer, endOfData);
        crc32.reset();
        crc32.update(byteBuffer);
        final int checksumValue = (int)crc32.getValue();
        buffer.putInt(checksumOffset, checksumValue);
    }

    public void onDisconnect(final long sessionId)
    {
        currentlyAuthenticatedSessionIds.remove(sessionId);
    }

    public void reset(final File backupLocation)
    {
        if (!currentlyAuthenticatedSessionIds.isEmpty())
        {
            throw new IllegalStateException(
                "There are currently authenticated sessions: " + currentlyAuthenticatedSessionIds);
        }

        counter = LOWEST_VALID_SESSION_ID;
        compositeToContext.clear();
        allSessions.clear();

        if (backupLocation != null)
        {
            mappedFile.transferTo(backupLocation);
        }

        buffer.setMemory(0, buffer.capacity(), (byte)0);
        initialiseBuffer();
    }

    void updateSavedData(
        final int filePosition,
        final int sequenceIndex,
        final long logonTime,
        final long lastSequenceResetTime)
    {
        sessionIdEncoder
            .wrap(buffer, filePosition)
            .sequenceIndex(sequenceIndex)
            .logonTime(logonTime)
            .lastSequenceResetTime(lastSequenceResetTime);

        final int start = nextSectorStart(filePosition) - SECTOR_SIZE;
        final int checksumOffset = start + SECTOR_DATA_LENGTH;
        updateChecksum(start, checksumOffset);

        mappedFile.force();
    }

    long lookupSessionId(final CompositeKey compositeKey)
    {
        final SessionContext sessionContext = compositeToContext.get(compositeKey);
        if (sessionContext == null)
        {
            return Session.UNKNOWN;
        }
        return sessionContext.sessionId();
    }

    boolean isAuthenticated(final long sessionId)
    {
        return currentlyAuthenticatedSessionIds.contains(sessionId);
    }

    boolean isKnownSessionId(final long sessionId)
    {
        return lookupById(sessionId) != null;
    }

    public List<SessionInfo> allSessions()
    {
        return allSessions;
    }
}
