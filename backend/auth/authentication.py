import hmac

from fastapi import HTTPException


class Authentication:
    def __init__(self, settings):
        self.settings = settings
        self.firebase_app = None
        if settings.mode == "PRODUCTION":
            import firebase_admin

            self.firebase_app = firebase_admin.initialize_app(
                options={"projectId": settings.firebase_project}
            )

    def user(self, authorization: str | None) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "Authentication required")
        token = authorization[7:]
        if self.settings.mode != "PRODUCTION":
            if not hmac.compare_digest(token, self.settings.dev_token):
                raise HTTPException(401, "Invalid development token")
            return "local-student"
        try:
            from firebase_admin import auth

            return auth.verify_id_token(token, app=self.firebase_app, check_revoked=True)["uid"]
        except Exception:
            raise HTTPException(401, "Invalid or expired identity token") from None
