"""Replace the provider here to connect another forecast API without changing the app contract."""

from datetime import datetime, timedelta, timezone

import httpx


class MockWeatherProvider:
    def forecast(self, latitude, longitude):
        current_hour = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
        return {
            "mock": True,
            "hourly": [
                {
                    "time": (current_hour + timedelta(hours=i)).strftime("%Y-%m-%dT%H:%MZ"),
                    "rain_probability": 75,
                    "temperature": 29.0,
                    "weather_code": 61,
                }
                for i in range(48)
            ],
        }


class OpenMeteoWeatherProvider:
    def forecast(self, latitude, longitude):
        response = httpx.get(
            "https://api.open-meteo.com/v1/forecast",
            params={
                "latitude": latitude,
                "longitude": longitude,
                "hourly": "precipitation_probability,temperature_2m,weather_code",
                "forecast_days": 2,
                "timezone": "UTC",
            },
            timeout=5,
        )
        response.raise_for_status()
        data = response.json()["hourly"]
        times = data["time"]
        probabilities = data["precipitation_probability"]
        temperatures = data["temperature_2m"]
        codes = data["weather_code"]
        if not (len(times) == len(probabilities) == len(temperatures) == len(codes)):
            raise ValueError("Invalid hourly forecast lengths")
        return {
            "mock": False,
            "hourly": [
                {
                    "time": stamp + "Z",
                    "rain_probability": probability,
                    "temperature": temperature,
                    "weather_code": code,
                }
                for stamp, probability, temperature, code in zip(times, probabilities, temperatures, codes)
            ],
        }
