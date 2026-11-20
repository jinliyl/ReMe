"""Strict, application-local contracts for the Android diary."""

from datetime import date as Date
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, PrivateAttr, model_validator, field_validator

Identifier = Annotated[str, Field(pattern=r"^[a-zA-Z0-9_-]{1,80}$")]


class Model(BaseModel):
    """Reject caller-controlled framework context and misspelled fields."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False, hide_input_in_errors=True)


class Connect(Model):
    """Identify the mobile client and query supported capabilities."""

    device_id: Identifier
    protocol_version: Literal[1] = 1


class Capture(Model):
    """An immutable text or PCM recording captured on the phone."""

    capture_id: Identifier
    captured_at: AwareDatetime
    timezone: str = "Asia/Shanghai"
    kind: Literal["text", "audio", "share"] = "text"
    text: str | None = Field(default=None, max_length=20000)
    audio: str | None = Field(default=None, max_length=12 * 1024 * 1024)
    audio_format: Literal["wav"] = "wav"
    sample_rate: Literal[16000] = 16000
    target_kind: Literal["general", "idea", "task"] = "general"
    target_id: Identifier | None = None

    @field_validator("timezone")
    @classmethod
    def timezone_exists(cls, value):
        """Require an IANA timezone supported by the server."""
        try:
            ZoneInfo(value)
        except KeyError as exc:
            raise ValueError("unknown IANA timezone") from exc
        return value

    @model_validator(mode="after")
    def input_is_complete(self):
        """Check mutually exclusive inputs and normalize capture time."""
        self.captured_at = self.captured_at.astimezone(ZoneInfo(self.timezone))
        if self.kind == "audio":
            if not self.audio or self.text is not None:
                raise ValueError("audio input requires audio and no text")
        elif not self.text or not self.text.strip() or self.audio is not None:
            raise ValueError("text input requires nonempty text and no audio")
        if (self.target_kind == "general") != (self.target_id is None):
            raise ValueError("target_kind and target_id must identify the same object")
        return self


class Events(Model):
    """Observe one durable processing run."""

    run_id: Identifier


class Read(Model):
    """Read only typed workspace objects with bounded pagination."""

    kind: Literal["journal", "task", "idea", "run", "conversation", "research", "resource"]
    id: Identifier | None = None
    date: Date | None = None
    offset: int = Field(default=0, ge=0)
    limit: int = Field(default=30, ge=1, le=100)


class Sync(Model):
    """Read a page of durable commits after an opaque cursor."""

    device_id: Identifier
    cursor: str | None = Field(default=None, max_length=200)
    limit: int = Field(default=100, ge=1, le=100)


class Update(Model):
    """An idempotent user action against an expected source revision."""

    operation_id: Identifier
    kind: Literal["task", "idea", "run"]
    id: Identifier
    action: Literal["complete", "cancel", "reopen", "reschedule", "pause", "resume", "correct", "retry"]
    expected_revision: int = Field(ge=1)
    text: str | None = Field(default=None, max_length=20000)
    scheduled_at: AwareDatetime | None = None
    due_at: AwareDatetime | None = None
    remind_at: AwareDatetime | None = None

    @model_validator(mode="after")
    def action_fields_match(self):
        """Reject action fields that would otherwise be silently ignored."""
        actions = {
            "task": {"complete", "cancel", "reopen", "reschedule"},
            "idea": {"pause", "resume"},
            "run": {"cancel", "correct", "retry"},
        }
        if self.action not in actions[self.kind]:
            raise ValueError("unsupported action for this object")
        times = self.model_fields_set & {"scheduled_at", "due_at", "remind_at"}
        if bool(times) != (self.action == "reschedule"):
            raise ValueError("time fields are required only for reschedule")
        if self.action == "correct":
            if self.text is None or not self.text.strip():
                raise ValueError("correction requires nonempty text")
        elif self.text is not None:
            raise ValueError("text is supported only for correction")
        return self


class Speech(Model):
    """Synthesize an already published assistant message."""

    run_id: Identifier
    message_id: Identifier
    voice: str | None = Field(default=None, max_length=80)


class Ack(Model):
    """Record device delivery state without assuming the user read it."""

    operation_id: Identifier
    device_id: Identifier
    reminder_id: str = Field(min_length=1, max_length=250)
    state: Literal["synced", "registered", "posted", "read"]


class Document(Model):
    """Canonical Markdown frontmatter and body for a task or idea."""

    _source_fingerprint: str = PrivateAttr(default="")

    @property
    def source_fingerprint(self) -> str:
        """The exact file read for inference, excluded from serialized domain data."""
        return self._source_fingerprint

    @source_fingerprint.setter
    def source_fingerprint(self, value: str):
        self._source_fingerprint = value

    id: Identifier
    kind: Literal["task", "idea"]
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(default="", max_length=50000)
    revision: int = Field(default=1, ge=1)
    input_revision: int = Field(default=1, ge=1)
    status: str = "open"
    scheduled_at: AwareDatetime | None = None
    due_at: AwareDatetime | None = None
    remind_at: AwareDatetime | None = None
    scheduled_date: Date | None = None
    due_date: Date | None = None
    timezone: str = "Asia/Shanghai"
    schedule_revision: int = Field(default=1, ge=1)
    related_ideas: list[Identifier] = Field(default_factory=list)
    related_tasks: list[Identifier] = Field(default_factory=list)
    source_ids: list[Identifier] = Field(default_factory=list)
    researched_input_revision: int = Field(default=0, ge=0)
    last_operation_id: str = ""

    @model_validator(mode="after")
    def valid_status(self):
        """Keep document states and timezones within the domain contract."""
        allowed = (
            {"open", "done", "cancelled"}
            if self.kind == "task"
            else {"collecting", "researching", "ready_for_discussion", "adopted", "paused", "archived"}
        )
        if self.status not in allowed:
            raise ValueError("invalid document status")
        Capture.timezone_exists(self.timezone)
        return self


class Proposal(Model):
    """A model draft validated before any source is changed."""

    id: Identifier | None = None
    expected_revision: int | None = Field(default=None, ge=1)
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(max_length=20000)
    scheduled_at: AwareDatetime | None = None
    due_at: AwareDatetime | None = None
    remind_at: AwareDatetime | None = None
    time_evidence: str = Field(default="", max_length=500)
    related_ids: list[Identifier] = Field(default_factory=list)


class DiaryDraft(Model):
    """The spoken response and proposed diary organization."""

    answer: str = Field(min_length=1, max_length=20000)
    journal: str = Field(min_length=1, max_length=20000)
    tasks: list[Proposal] = Field(default_factory=list, max_length=12)
    ideas: list[Proposal] = Field(default_factory=list, max_length=12)


class ResearchDraft(Model):
    """Evidence and questions prepared for a future discussion."""

    summary: str = Field(min_length=1, max_length=20000)
    sources: list[str] = Field(default_factory=list, max_length=20)
    questions: list[str] = Field(default_factory=list, max_length=5)
