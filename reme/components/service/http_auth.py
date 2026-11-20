"""Optional bearer authentication before request bodies and jobs are processed."""

import asyncio
import secrets

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator
from starlette.responses import JSONResponse


class HttpAuthConfig(BaseModel):
    """Keep credentials out of representations and serialized configuration."""

    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    enabled: bool = False
    token: SecretStr = Field(default=SecretStr(""), exclude=True)

    @model_validator(mode="after")
    def validate_token(self):
        """Fail closed when authentication was requested without a usable secret."""
        value = self.token.get_secret_value()
        if self.enabled and (len(value.encode()) < 32 or any(char.isspace() for char in value)):
            raise ValueError("HTTP authentication requires a token of at least 32 bytes without whitespace")
        return self


class HttpAccessMiddleware:
    """Authenticate all HTTP routes and bound bodies without buffering SSE replies."""

    def __init__(self, app, *, auth: HttpAuthConfig, max_request_bytes: int | None = None):
        self.app = app
        self.auth = auth
        self.max_request_bytes = max_request_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        if self.auth.enabled:
            values = [value for key, value in scope["headers"] if key.lower() == b"authorization"]
            parts = values[0].split(b" ", 1) if len(values) == 1 else []
            expected = self.auth.token.get_secret_value().encode()
            if len(parts) != 2 or parts[0].lower() != b"bearer" or not secrets.compare_digest(parts[1], expected):
                await JSONResponse(
                    {"detail": "Authentication required"},
                    status_code=401,
                    headers={"WWW-Authenticate": "Bearer", "Cache-Control": "no-store"},
                )(scope, receive, send)
                return
        if self.max_request_bytes is None:
            await self.app(scope, receive, send)
            return
        body = bytearray()
        try:
            async with asyncio.timeout(60):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    body.extend(message.get("body", b""))
                    if len(body) > self.max_request_bytes:
                        await JSONResponse({"detail": "Request too large"}, status_code=413)(scope, receive, send)
                        return
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            await JSONResponse({"detail": "Request body timed out"}, status_code=408)(scope, receive, send)
            return
        pending = True

        async def replay():
            nonlocal pending
            if pending:
                pending = False
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)
