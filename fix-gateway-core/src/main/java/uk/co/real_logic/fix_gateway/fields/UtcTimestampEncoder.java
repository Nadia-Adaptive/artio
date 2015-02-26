/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.fields;

import uk.co.real_logic.fix_gateway.util.MutableAsciiFlyweight;

import static uk.co.real_logic.fix_gateway.fields.CalendricalUtil.*;

public final class UtcTimestampEncoder
{

    public static final long MIN_EPOCH_MILLIS = UtcTimestampDecoder.MIN_EPOCH_MILLIS;
    public static final long MAX_EPOCH_MILLIS = UtcTimestampDecoder.MAX_EPOCH_MILLIS;

    public static final int LENGTH_WITH_MILLISECONDS = 21;
    public static final int LENGTH_WITHOUT_MILLISECONDS = 17;

    private UtcTimestampEncoder()
    {
    }

    public static int encode(final long epochMillis, final MutableAsciiFlyweight string, final int offset)
    {
        if (epochMillis < MIN_EPOCH_MILLIS || epochMillis > MAX_EPOCH_MILLIS)
        {
            throw new IllegalArgumentException(epochMillis + " is outside of the valid range for this encoder");
        }

        final long localSecond = Math.floorDiv(epochMillis, MILLIS_IN_SECOND);
        final long epochDay = Math.floorDiv(localSecond, SECONDS_IN_DAY);
        final int fractionOfSecond = (int) (Math.floorMod(epochMillis, MILLIS_IN_SECOND));

        encodeDate(epochDay, string, offset);
        string.putChar(8, '-');
        encodeTime(localSecond, fractionOfSecond, string, offset + 9);

        return fractionOfSecond > 0 ? LENGTH_WITH_MILLISECONDS : LENGTH_WITHOUT_MILLISECONDS;
    }


    private static void encodeTime(
        final long epochSecond,
        final int epochMillis,
        final MutableAsciiFlyweight string,
        final int offset)
    {
        int secondOfDay = (int) Math.floorMod(epochSecond, SECONDS_IN_DAY);
        int hours = secondOfDay / SECONDS_IN_HOUR;
        secondOfDay -= hours * SECONDS_IN_HOUR;
        int minutes = secondOfDay / SECONDS_IN_MINUTE;
        secondOfDay -= minutes * SECONDS_IN_MINUTE;

        string.putNatural(offset, 2, hours);
        string.putChar(offset + 2, ':');
        string.putNatural(offset + 3, 2, minutes);
        string.putChar(offset + 5, ':');
        string.putNatural(offset + 6, 2, secondOfDay);

        if (epochMillis > 0)
        {
            string.putChar(offset + 8, '.');
            string.putNatural(offset + 9, 3, epochMillis);
        }
    }

}
