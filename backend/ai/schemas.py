import base64
import io
from datetime import date
from typing import Literal

from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ExtractionInput(StrictModel):
    text: str = Field(default="", max_length=8000)
    image_base64: str = Field(default="", max_length=2_800_000)
    mime_type: Literal["image/png", "image/jpeg"] = "image/png"
    reference_date: date

    @model_validator(mode="after")
    def valid_input(self):
        if bool(self.text.strip()) == bool(self.image_base64):
            raise ValueError("Provide text OR a PNG/JPEG image")
        if self.image_base64:
            try:
                raw = base64.b64decode(self.image_base64, validate=True)
                if len(raw) > 2_000_000:
                    raise ValueError("Image exceeds 2 MB")
                with Image.open(io.BytesIO(raw)) as image:
                    if image.format not in {"PNG", "JPEG"} or image.width * image.height > 12_000_000:
                        raise ValueError("Unsupported or oversized image")
                    image.load()
                    # Re-encode pixels to strip EXIF and other unnecessary metadata.
                    clean = io.BytesIO()
                    image.convert("RGB").save(clean, format="JPEG", quality=85)
                    self.image_base64 = base64.b64encode(clean.getvalue()).decode()
                    self.mime_type = "image/jpeg"
            except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as error:
                raise ValueError("Invalid image") from error
        return self


class ExtractedEvent(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    course: str = Field(default="", max_length=100)
    day: Literal["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    start_time: str = Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    end_time: str = Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    location: str = Field(default="", max_length=200)

    @model_validator(mode="after")
    def time_order(self):
        if self.end_time <= self.start_time:
            raise ValueError("End must follow start")
        return self


class Timetable(StrictModel):
    events: list[ExtractedEvent] = Field(min_length=1, max_length=50)


class ExtractedTask(StrictModel):
    title: str = Field(min_length=1, max_length=300)
    duration: int = Field(default=60, ge=1, le=1440)


class Instruction(StrictModel):
    related_event: str = Field(max_length=200)
    date: date
    required_items: list[str] = Field(max_length=30)
    tasks: list[ExtractedTask] = Field(max_length=30)


SCHEMAS = {"timetable": Timetable, "instruction": Instruction}
