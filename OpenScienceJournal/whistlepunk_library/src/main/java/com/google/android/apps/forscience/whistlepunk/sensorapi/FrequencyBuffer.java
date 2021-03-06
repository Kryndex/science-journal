/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.sensorapi;

import com.google.android.apps.forscience.whistlepunk.sensordb.ScalarReading;

import java.util.LinkedList;
import java.util.List;

public class FrequencyBuffer implements ValueFilter {
    private List<ScalarReading> mReadings = new LinkedList<>();

    private long mWindow;
    private final double mDenominatorInMillis;
    private double mFilter;

    /**
     * @param windowMillis how many milliseconds of data to keep for frequency detection
     * @param denominatorInMillis how many milliseconds are in the display unit (for Hz, this
     *                            should be 1000.  For RPM, it should be 60,000)
     * @param filter only consider signals with an amplitude at least twice this number.
     */
    public FrequencyBuffer(long windowMillis, double denominatorInMillis, double filter) {
        mWindow = windowMillis;
        mDenominatorInMillis = denominatorInMillis;
        mFilter = filter;
    }

    public void changeWindow(long newWindowMillis) {
        mWindow = newWindowMillis;
        if (!mReadings.isEmpty()) {
            prune(getNewestTimestamp());
        }
    }

    @Override
    public double filterValue(long timestamp, double value) {
        mReadings.add(new ScalarReading(timestamp, value));
        prune(timestamp);
        return getLatestFrequency();
    }

    private void prune(long timestamp) {
        long oldestRemaining = timestamp - mWindow;
        while (mReadings.get(0).getCollectedTimeMillis() < oldestRemaining) {
            mReadings.remove(0);
        }
    }

    public double getLatestFrequency() {
        if (mReadings.size() < 2) {
            return 0.0;
        }

        double average = computeAverageValue();
        int crossings = 0;
        long firstCrossingTime = -1;
        long lastCrossingTime = -1;

        boolean higherThanAverage = mReadings.get(0).getValue() > average;
        for (ScalarReading reading : mReadings.subList(1, mReadings.size())) {
            boolean thisReadingHigher = reading.getValue() > average;
            if (higherThanAverage != thisReadingHigher) {
                higherThanAverage = thisReadingHigher;
                crossings++;
                if (firstCrossingTime == -1) {
                    firstCrossingTime = reading.getCollectedTimeMillis();
                } else {
                    lastCrossingTime = reading.getCollectedTimeMillis();
                }
            }
        }
        // Drop the leading cross because that's where time starts
        crossings--;

        if (firstCrossingTime == -1 || lastCrossingTime == -1) {
            return 0.0;
        }

        long adjustedWindowMillis = lastCrossingTime - firstCrossingTime;

        if (adjustedWindowMillis < mWindow / 4) {
            // if the signal appears to have stopped 3/4 a window ago, then treat it as stopped.
            // Without this, we can read very or infinitely short single spikes as representing a
            // nonsensical, very high "frequency", leading to janky frequency "spikes" when
            // signals stop and start.
            return 0.0;
        }

        double adjustedWindowUserUnits = adjustedWindowMillis / mDenominatorInMillis;
        double cycles = crossings / 2.0f;
        double userUnitFrequency = cycles / adjustedWindowUserUnits;
        return userUnitFrequency;
    }

    private double computeAverageValue() {
        // TODO: if readings are not somewhat evenly distributed in time, we should weight
        // low-sampling-rate readings more heavily than high-sampling-rate.  But we'll just
        // assume for now that doesn't happen.

        double total = 0;
        for (ScalarReading reading : mReadings) {
            total += reading.getValue();
        }
        // Adding mFilter means that variations of less than mFilter won't register as cycles.
        return total / mReadings.size() + mFilter;
    }

    private long getNewestTimestamp() {
        final ScalarReading mostRecentReading = mReadings.get(mReadings.size() - 1);
        return mostRecentReading.getCollectedTimeMillis();
    }

    public void changeFilter(double newFilter) {
        mFilter = newFilter;
    }
}
