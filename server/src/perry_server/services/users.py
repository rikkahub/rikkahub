from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from perry_server.models.user import User

DEFAULT_USER_NAME = "owner"


async def get_or_create_primary_user(session: AsyncSession) -> User:
    result = await session.execute(select(User).order_by(User.created_at.asc()).limit(1))
    user = result.scalar_one_or_none()
    if user is not None:
        return user
    user = User(display_name=DEFAULT_USER_NAME)
    session.add(user)
    await session.flush()
    return user
