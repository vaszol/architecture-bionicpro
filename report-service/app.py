from fastapi import FastAPI, HTTPException, Cookie
from fastapi.responses import PlainTextResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import httpx
import json

app = FastAPI(title="BionicPRO Report Service")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

CLICKHOUSE_URL = "http://clickhouse:8123"

async def get_user_id_from_session(session_cookie: str) -> str:
    """Получение user_id из сессии через auth сервис"""
    async with httpx.AsyncClient() as client:
        response = await client.get(
            'http://bionicpro-auth:8001/api/auth/userinfo',
            cookies={'bionicpro_session': session_cookie}
        )
        if response.status_code != 200:
            raise HTTPException(status_code=401, detail="Not authenticated")
        user_info = response.json()
        return user_info.get("userId") or user_info.get("user_id") or "unknown"

@app.get("/api/reports")
async def get_report(bionicpro_session: str = Cookie(None)):
    """Получение отчёта из новой витрины (prosthetic_reports_mv)"""

    if not bionicpro_session:
        raise HTTPException(status_code=401, detail="Not authenticated")

    try:
        # Проверка аутентификации
        async with httpx.AsyncClient() as client:
            auth_response = await client.get(
                'http://bionicpro-auth:8001/api/auth/check',
                cookies={'bionicpro_session': bionicpro_session}
            )
            if auth_response.status_code != 200:
                raise HTTPException(status_code=401, detail="Not authenticated")

        user_id = await get_user_id_from_session(bionicpro_session)

        # Используем новую витрину prosthetic_reports_mv
        query = f"""
        SELECT 
            report_date,
            prosthetic_id,
            user_id,
            signal_count,
            signal_avg,
            response_time_avg,
            battery_avg,
            performance_score
        FROM prosthetic_reports_mv
        WHERE user_id = '{user_id}'
        ORDER BY report_date DESC
        LIMIT 100
        FORMAT JSONEachRow
        """

        async with httpx.AsyncClient() as client:
            response = await client.post(CLICKHOUSE_URL, data=query, timeout=10.0)
            data = response.text

        if not data or data.strip() == "":
            return PlainTextResponse(content=f"Нет данных для пользователя {user_id}")

        report = f"Отчёт по пользователю {user_id}\n"
        report += "=" * 50 + "\n\n"

        for line in data.strip().split('\n')[:10]:
            try:
                row = json.loads(line)
                report += f"Дата: {row.get('report_date', 'N/A')}\n"
                report += f"  Протез: {row.get('prosthetic_id', 'N/A')}\n"
                report += f"  Производительность: {row.get('performance_score', 'N/A')}\n"
                report += f"  Время реакции: {row.get('response_time_avg', 0):.0f} мс\n"
                report += f"  Уровень заряда: {row.get('battery_avg', 0):.0f}%\n"
                report += f"  Сигналов: {row.get('signal_count', 0)}\n"
                report += "-" * 30 + "\n"
            except:
                pass

        return PlainTextResponse(content=report)

    except Exception as e:
        print(f"Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/api/reports/health")
async def health():
    return {"status": "ok"}