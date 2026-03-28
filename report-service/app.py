from fastapi import FastAPI, HTTPException, Cookie
from fastapi.responses import PlainTextResponse
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

@app.get("/api/reports/{prosthetic_id}")
async def get_report(prosthetic_id: str, bionicpro_session: str = Cookie(None)):
    """Получение отчёта по протезу"""

    # Проверка аутентификации
    if not bionicpro_session:
        raise HTTPException(status_code=401, detail="Not authenticated")

    async with httpx.AsyncClient() as client:
        response = await client.get(
            'http://bionicpro-auth:8001/api/auth/check',
            cookies={'bionicpro_session': bionicpro_session}
        )
        if response.status_code != 200:
            raise HTTPException(status_code=401, detail="Not authenticated")

    # Получаем данные
    query = f"""
    SELECT 
        toDate(report_date) as date,
        prosthetic_id,
        user_id,
        signal_count,
        signal_avg,
        response_time_avg,
        battery_avg,
        performance_score
    FROM prosthetic_reports
    WHERE prosthetic_id = '{prosthetic_id}'
    ORDER BY date DESC
    LIMIT 100
    FORMAT JSONEachRow
    """

    async with httpx.AsyncClient() as client:
        response = await client.post(CLICKHOUSE_URL, data=query)
        data = response.text

    if not data or data.strip() == "":
        return PlainTextResponse(content=f"Нет данных для протеза {prosthetic_id}")

    # Формируем отчёт
    report = f"Отчёт по протезу {prosthetic_id}\n"
    report += "=" * 50 + "\n\n"

    lines = data.strip().split('\n')
    for line in lines[:10]:
        try:
            row = json.loads(line)
            report += f"Дата: {row.get('date', 'N/A')}\n"
            report += f"  Производительность: {row.get('performance_score', 'N/A')}\n"
            report += f"  Время реакции: {row.get('response_time_avg', 0):.0f} мс\n"
            report += f"  Уровень заряда: {row.get('battery_avg', 0):.0f}%\n"
            report += f"  Сигналов: {row.get('signal_count', 0)}\n"
            report += "-" * 30 + "\n"
        except:
            pass

    return PlainTextResponse(content=report)