import threading
import time

import httpx


class WeatherService:
    def __init__(self, mock=False):
        self.mock = mock
        self.cache = {}
        self.lock = threading.Lock()

    def forecast(self, latitude, longitude):
        if self.mock:
            return {
                "rain_probability": 75,
                "hourly": [],
                "mock": True,
                "available": True,
            }

        key = (round(latitude, 2), round(longitude, 2))

        with self.lock:
            cached = self.cache.get(key)
            if cached and cached[0] > time.monotonic():
                return cached[1]

        try:
            response = httpx.get(
                "https://api.open-meteo.com/v1/forecast",
                params={
                    "latitude": key[0],
                    "longitude": key[1],

                    # Keep today's maximum rain probability
                    # for backward compatibility.
                    "daily": "precipitation_probability_max",

                    # Hourly forecast used later for determining
                    # whether rain may occur while the student
                    # is outside.
                    "hourly": (
                        "precipitation_probability,"
                        "temperature_2m,"
                        "weather_code"
                    ),

                    # Today + tomorrow.
                    "forecast_days": 2,
                    "timezone": "auto",
                },
                timeout=5,
            )

            response.raise_for_status()
            data = response.json()

            probability = data["daily"]["precipitation_probability_max"][0]

            if (
                not isinstance(probability, (int, float))
                or not 0 <= probability <= 100
            ):
                raise ValueError("Invalid weather")

            hourly_data = data["hourly"]

            times = hourly_data["time"]
            probabilities = hourly_data["precipitation_probability"]
            temperatures = hourly_data["temperature_2m"]
            weather_codes = hourly_data["weather_code"]

            if not (
                len(times)
                == len(probabilities)
                == len(temperatures)
                == len(weather_codes)
            ):
                raise ValueError("Invalid hourly weather")

            hourly = []

            for timestamp, rain_probability, temperature, weather_code in zip(
                times,
                probabilities,
                temperatures,
                weather_codes,
            ):
                if (
                    not isinstance(rain_probability, (int, float))
                    or not 0 <= rain_probability <= 100
                ):
                    raise ValueError("Invalid hourly rain probability")

                if not isinstance(temperature, (int, float)):
                    raise ValueError("Invalid hourly temperature")

                if not isinstance(weather_code, int):
                    raise ValueError("Invalid weather code")

                hourly.append(
                    {
                        "time": timestamp,
                        "rain_probability": rain_probability,
                        "temperature": temperature,
                        "weather_code": weather_code,
                    }
                )

            result = {
                "rain_probability": probability,
                "hourly": hourly,
                "mock": False,
                "available": True,
            }

            with self.lock:
                if len(self.cache) >= 1000:
                    self.cache.clear()

                self.cache[key] = (
                    time.monotonic() + 3600,
                    result,
                )

            return result

        except (
            httpx.HTTPError,
            ValueError,
            KeyError,
            IndexError,
            TypeError,
        ):
            return {
                "rain_probability": None,
                "hourly": [],
                "mock": False,
                "available": False,
            }