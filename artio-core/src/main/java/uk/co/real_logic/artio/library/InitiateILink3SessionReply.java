/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.library;

import uk.co.real_logic.artio.ilink.ILink3Session;
import uk.co.real_logic.artio.ilink.ILink3SessionConfiguration;

import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;

/**
 * .
 */
class InitiateILink3SessionReply extends LibraryReply<ILink3Session>
{
    private final ILink3SessionConfiguration configuration;
    private long connectionId = NO_CONNECTION_ID;

    InitiateILink3SessionReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTime,
        final ILink3SessionConfiguration configuration)
    {
        super(libraryPoller, latestReplyArrivalTime);
        this.configuration = configuration;
        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    protected void sendMessage()
    {
        final long position = libraryPoller.saveInitiateILink(correlationId, configuration);

        requiresResend = position < 0;
    }

    void onComplete(final ILink3Session result)
    {
        libraryPoller.deregister(correlationId);
        super.onComplete(result);
    }

    protected boolean onTimeout()
    {
        // TODO: do we need this?
//        libraryPoller.onInitiatorSessionTimeout(correlationId, connectionId);

        return super.onTimeout();
    }

    ILink3SessionConfiguration configuration()
    {
        return configuration;
    }
}
