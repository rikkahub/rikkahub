from fastapi import APIRouter

from perry_server.api import devices, health, server_info, sync


def build_api_router() -> APIRouter:
    router = APIRouter()
    router.include_router(health.router)
    router.include_router(devices.router)
    router.include_router(server_info.router)
    router.include_router(sync.router)
    return router
