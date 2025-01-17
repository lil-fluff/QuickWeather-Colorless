/*
 *   Copyright 2019 - 2023 Tyler Williamson
 *
 *   This file is part of QuickWeather.
 *
 *   QuickWeather is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   QuickWeather is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with QuickWeather.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.ominous.quickweather.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ominous.quickweather.R;
import com.ominous.quickweather.api.Gadgetbridge;
import com.ominous.quickweather.data.WeatherDataManager;
import com.ominous.quickweather.data.WeatherModel;
import com.ominous.quickweather.data.WeatherResponseOneCall;
import com.ominous.quickweather.pref.WeatherPreferences;
import com.ominous.quickweather.util.NotificationUtils;

public class WeatherWorker extends Worker {
    public final static String KEY_ERROR_MESSAGE = "key_error_message", KEY_STACK_TRACE = "key_stack_trace";

    public WeatherWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        WeatherWorkManager.enqueueNotificationWorker(getApplicationContext(), true);

        WeatherModel weatherModel = WeatherDataManager.getInstance().getBackgroundWeatherModel(getApplicationContext());

        switch (weatherModel.status) {
            case SUCCESS:
                if (WeatherPreferences.getInstance(getApplicationContext()).shouldDoGadgetbridgeBroadcast() &&
                        weatherModel.weatherLocation != null &&
                        weatherModel.responseOneCall != null) {
                    Gadgetbridge.getInstance().broadcastWeather(getApplicationContext(), weatherModel.weatherLocation, weatherModel.responseOneCall);
                }

                if (weatherModel.responseOneCall != null && weatherModel.responseOneCall.alerts != null && WeatherPreferences.getInstance(getApplicationContext()).shouldShowAlertNotification()) {
                    for (WeatherResponseOneCall.Alert alert : weatherModel.responseOneCall.alerts) {
                        NotificationUtils.makeAlert(getApplicationContext(), alert);
                    }
                }

                if (WeatherPreferences.getInstance(getApplicationContext()).shouldShowPersistentNotification()) {
                    NotificationUtils.updatePersistentNotification(getApplicationContext(), weatherModel.weatherLocation, weatherModel.responseOneCall);
                }

                //TODO Worker Success data?
                return Result.success(Data.EMPTY);
            case ERROR_OTHER:
                if (getRunAttemptCount() < 3) {
                    return Result.retry();
                } else {
                    NotificationUtils.makeError(
                            getApplicationContext(),
                            getApplicationContext().getString(R.string.error_obtaining_weather),
                            weatherModel.errorMessage);
                }
            case ERROR_LOCATION_DISABLED:
            case ERROR_LOCATION_ACCESS_DISALLOWED:
            default:
                return Result.failure(new Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, weatherModel.errorMessage)
                        .putString(KEY_STACK_TRACE, Log.getStackTraceString(weatherModel.error))
                        .build());
        }
    }
}
