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

package com.ominous.quickweather.data;

import android.content.Context;
import android.location.Location;
import android.util.Pair;

import androidx.lifecycle.MutableLiveData;

import com.ominous.quickweather.R;
import com.ominous.quickweather.api.OpenWeatherMap;
import com.ominous.quickweather.location.WeatherLocationManager;
import com.ominous.quickweather.pref.ApiVersion;
import com.ominous.quickweather.pref.WeatherPreferences;
import com.ominous.tylerutils.async.Promise;
import com.ominous.tylerutils.http.HttpException;

import org.json.JSONException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public enum WeatherDataManager {
    INSTANCE;

    private final Map<Pair<Double, Double>, WeatherResponseOneCall> oneCallResponseCache = new HashMap<>();
    private final Map<Pair<Double, Double>, WeatherResponseForecast> forecastResponseCache = new HashMap<>();
    private final int CACHE_EXPIRATION = 60 * 1000; //1 minute
    private final int MAX_ATTEMPTS = 3;
    private final int ATTEMPT_SLEEP_DURATION = 5000;
    private final WeatherLocationManager weatherLocationManager = WeatherLocationManager.getInstance();

    @SuppressWarnings("SameReturnValue")
    public static WeatherDataManager getInstance() {
        return INSTANCE;
    }

    public void updateWeatherLiveData(Context context, MutableLiveData<WeatherModel> weatherLiveData, boolean obtainForecast) {
        Promise.create(a -> {
            weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.UPDATING, null, null));

            try {
                WeatherDatabase.WeatherLocation weatherLocation = WeatherDatabase.getInstance(context).locationDao().getSelected();
                Pair<Double, Double> locationKey;

                if (weatherLocation.isCurrentLocation) {
                    Location location = weatherLocationManager.getLastKnownLocation(context, false);

                    if (location == null) {
                        weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.OBTAINING_LOCATION, null, null));

                        location = weatherLocationManager.obtainCurrentLocation(context, false);

                        if (location == null) {
                            weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_null_location), null));
                            return;
                        }
                    }

                    locationKey = new Pair<>(
                            BigDecimal.valueOf(location.getLatitude()).setScale(3, RoundingMode.HALF_UP).doubleValue(),
                            BigDecimal.valueOf(location.getLongitude()).setScale(3, RoundingMode.HALF_UP).doubleValue()
                    );
                } else {
                    locationKey = new Pair<>(
                            weatherLocation.latitude,
                            weatherLocation.longitude
                    );
                }

                WeatherResponseOneCall responseOneCall = getWeatherOneCall(context, locationKey);
                WeatherResponseForecast responseForecast = obtainForecast ? getWeatherForecast(context, locationKey) : null;

                if (responseOneCall == null || responseOneCall.current == null ||
                        (obtainForecast && (responseForecast == null || responseForecast.list == null))) {
                    weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_null_response), null));
                } else {
                    weatherLiveData.postValue(new WeatherModel(
                            responseOneCall,
                            responseForecast,
                            weatherLocation,
                            locationKey,
                            WeatherModel.WeatherStatus.SUCCESS));
                }
            } catch (WeatherLocationManager.LocationPermissionNotAvailableException e) {
                weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_LOCATION_ACCESS_DISALLOWED, context.getString(R.string.snackbar_background_location_notifications), e));
            } catch (WeatherLocationManager.LocationDisabledException e) {
                weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_LOCATION_DISABLED, context.getString(R.string.error_gps_disabled), e));
            } catch (IOException e) {
                weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_connecting_api), e));
            } catch (JSONException e) {
                weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_unexpected_api_result), e));
            } catch (InstantiationException | IllegalAccessException e) {
                weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_creating_result), e));
            } catch (HttpException e) {
                weatherLiveData.postValue(new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, e.getMessage(), e));
            }
        });
    }

    public WeatherModel getBackgroundWeatherModel(Context context) {
        try {
            WeatherDatabase.WeatherLocation weatherLocation = WeatherDatabase.getInstance(context).locationDao().getSelected();
            Pair<Double, Double> locationKey;

            if (weatherLocation.isCurrentLocation) {
                Location location = weatherLocationManager.getLastKnownLocation(context, true);

                if (location == null) {
                    location = weatherLocationManager.obtainCurrentLocation(context, true);

                    if (location == null) {
                        return new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_null_location), null);
                    }
                }

                locationKey = new Pair<>(
                        BigDecimal.valueOf(location.getLatitude()).setScale(3, RoundingMode.HALF_UP).doubleValue(),
                        BigDecimal.valueOf(location.getLongitude()).setScale(3, RoundingMode.HALF_UP).doubleValue()
                );
            } else {
                locationKey = new Pair<>(
                        weatherLocation.latitude,
                        weatherLocation.longitude
                );
            }

            WeatherResponseOneCall responseOneCall = getWeatherOneCall(context, locationKey);

            if (responseOneCall == null || responseOneCall.current == null) {
                return new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_null_response), null);
            } else {
                return new WeatherModel(
                        responseOneCall,
                        null,
                        weatherLocation,
                        locationKey,
                        WeatherModel.WeatherStatus.SUCCESS);
            }
        } catch (WeatherLocationManager.LocationPermissionNotAvailableException e) {
            return new WeatherModel(WeatherModel.WeatherStatus.ERROR_LOCATION_ACCESS_DISALLOWED, context.getString(R.string.snackbar_background_location_notifications), e);
        } catch (WeatherLocationManager.LocationDisabledException e) {
            return new WeatherModel(WeatherModel.WeatherStatus.ERROR_LOCATION_DISABLED, context.getString(R.string.error_gps_disabled), e);
        } catch (IOException e) {
            return new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_connecting_api), e);
        } catch (JSONException e) {
            return new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_unexpected_api_result), e);
        } catch (InstantiationException | IllegalAccessException e) {
            return new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, context.getString(R.string.error_creating_result), e);
        } catch (HttpException e) {
            return new WeatherModel(WeatherModel.WeatherStatus.ERROR_OTHER, e.getMessage(), e);
        }
    }

    private WeatherResponseOneCall getWeatherOneCall(Context context, Pair<Double, Double> locationKey) throws
            JSONException, HttpException, IOException, InstantiationException, IllegalAccessException {
        WeatherPreferences weatherPreferences = WeatherPreferences.getInstance(context);

        String apiKey = weatherPreferences.getAPIKey();
        ApiVersion apiVersion = weatherPreferences.getAPIVersion();

        if (apiVersion == ApiVersion.DEFAULT) {
            weatherPreferences.setAPIVersion(ApiVersion.ONECALL_2_5);
            apiVersion = ApiVersion.ONECALL_2_5;
        }

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        WeatherResponseOneCall newWeather = null;

        if (oneCallResponseCache.containsKey(locationKey)) {
            WeatherResponseOneCall previousWeather = oneCallResponseCache.get(locationKey);

            if (previousWeather != null && now.getTimeInMillis() - previousWeather.timestamp < CACHE_EXPIRATION) {
                return previousWeather;
            }
        }

        int attempt = 0;
        HttpException lastException = null;

        do {
            try {
                newWeather = OpenWeatherMap.getInstance().getWeatherOneCall(apiVersion, apiKey, locationKey.first, locationKey.second);
            } catch (HttpException e) {
                lastException = e;
                try {
                    Thread.sleep(ATTEMPT_SLEEP_DURATION);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            } catch (IOException | JSONException | InstantiationException |
                     IllegalAccessException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException("Uncaught Exception occurred");
            }
        } while (newWeather == null && attempt++ < MAX_ATTEMPTS);

        if (newWeather == null && lastException != null) {
            throw lastException;
        }

        oneCallResponseCache.put(locationKey, newWeather);
        return newWeather;
    }

    private WeatherResponseForecast getWeatherForecast(Context context, Pair<Double, Double> locationKey) throws
            JSONException, HttpException, IOException, InstantiationException, IllegalAccessException {
        String apiKey = WeatherPreferences.getInstance(context).getAPIKey();

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        WeatherResponseForecast newWeather = null;

        if (forecastResponseCache.containsKey(locationKey)) {
            WeatherResponseForecast previousWeather = forecastResponseCache.get(locationKey);

            if (previousWeather != null && now.getTimeInMillis() - previousWeather.timestamp * 1000 < CACHE_EXPIRATION) {
                return previousWeather;
            }
        }

        int attempt = 0;
        HttpException lastException = null;

        do {
            try {
                newWeather = OpenWeatherMap.getInstance().getWeatherForecast(apiKey, locationKey.first, locationKey.second);
            } catch (HttpException e) {
                lastException = e;
                try {
                    Thread.sleep(ATTEMPT_SLEEP_DURATION);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            } catch (IOException | JSONException | InstantiationException |
                     IllegalAccessException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException("Uncaught Exception occurred");
            }
        } while (newWeather == null && attempt++ < MAX_ATTEMPTS);

        if (newWeather == null && lastException != null) {
            throw lastException;
        }

        forecastResponseCache.put(locationKey, newWeather);
        return newWeather;
    }
}
