from fastapi import FastAPI, HTTPException, Cookie
from fastapi.responses import PlainTextResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import httpx
import json
import os
from datetime import datetime

app = FastAPI(title="BionicPRO Report Service")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Конфигурация
CLICKHOUSE_URL = "http://clickhouse:8123"
MINIO_URL = os.getenv("MINIO_URL", "http://minio:9002")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = "reports"
CDN_URL = os.getenv("CDN_URL", "http://localhost:8083")

async def get_user_id_from_session(session_cookie: str) -> str:
    """Получение user_id из сессии через auth сервис"""
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(
                'http://bionicpro-auth:8001/api/auth/userinfo',
                cookies={'bionicpro_session': session_cookie},
                timeout=5.0
            )
            if response.status_code != 200:
                raise HTTPException(status_code=401, detail="Not authenticated")

            user_info = response.json()
            return user_info.get("userId") or "unknown"
        except httpx.TimeoutException:
            raise HTTPException(status_code=503, detail="Auth service timeout")
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Auth error: {str(e)}")

def get_report_key(user_id: str, format: str = "txt") -> str:
    """Формирует ключ для хранения отчёта в S3"""
    today = datetime.now().strftime("%Y-%m-%d")
    return f"user_{user_id}/reports/{today}_report.{format}"

async def check_s3_object(key: str) -> bool:
    """Проверка существования объекта в MinIO"""
    try:
        url = f"{MINIO_URL}/{MINIO_BUCKET}/{key}"
        async with httpx.AsyncClient() as client:
            response = await client.head(url, auth=(MINIO_ACCESS_KEY, MINIO_SECRET_KEY), timeout=5.0)
            return response.status_code == 200
    except Exception:
        return False

async def put_s3_object(key: str, content: bytes, content_type: str):
    """Сохранение объекта в MinIO"""
    try:
        url = f"{MINIO_URL}/{MINIO_BUCKET}/{key}"
        async with httpx.AsyncClient() as client:
            await client.put(
                url,
                content=content,
                auth=(MINIO_ACCESS_KEY, MINIO_SECRET_KEY),
                headers={"Content-Type": content_type},
                timeout=10.0
            )
    except Exception as e:
        print(f"Error saving to MinIO: {e}")

async def get_report_data(user_id: str) -> tuple:
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

    try:
        async with httpx.AsyncClient() as client:
            response = await client.post(CLICKHOUSE_URL, data=query, timeout=10.0)
            data = response.text
    except Exception as e:
        return None, None

    if not data or data.strip() == "":
        return None, None

    # Формируем отчёт
    report = f"Отчёт по пользователю {user_id}\n"
    report += "=" * 50 + "\n\n"

    rows = []
    lines = data.strip().split('\n')
    for line in lines[:10]:
        try:
            row = json.loads(line)
            rows.append(row)
            report += f"Дата: {row.get('report_date', 'N/A')}\n"
            report += f"  Протез: {row.get('prosthetic_id', 'N/A')}\n"
            report += f"  Производительность: {row.get('performance_score', 'N/A')}\n"
            report += f"  Время реакции: {row.get('response_time_avg', 0):.0f} мс\n"
            report += f"  Уровень заряда: {row.get('battery_avg', 0):.0f}%\n"
            report += f"  Сигналов: {row.get('signal_count', 0)}\n"
            report += "-" * 30 + "\n"
        except:
            pass

    return report, rows

@app.get("/api/reports")
async def get_report(bionicpro_session: str = Cookie(None)):
    """Получение отчёта по протезу (только для своего протеза)"""

    # Проверка аутентификации
    if not bionicpro_session:
        raise HTTPException(status_code=401, detail="Not authenticated")

    try:
        # Получаем user_id из сессии
        user_id = await get_user_id_from_session(bionicpro_session)

        # Формируем ключ отчёта
        report_key = get_report_key(user_id, "txt")

        # 1. Проверяем наличие отчёта в MinIO
        exists = await check_s3_object(report_key)
        if exists:
            # Возвращаем CDN ссылку
            return JSONResponse({
                "status": "cached",
                "url": f"{CDN_URL}/{report_key}",
                "expires_in": 86400,
                "message": "Report found in cache"
            })

        # 2. Отчёт не найден - генерируем
        report_content, rows = await get_report_data(user_id)

        if not report_content:
            return PlainTextResponse(content=f"Нет данных для пользователя {user_id}")

        # 3. Сохраняем в MinIO
        await put_s3_object(report_key, report_content.encode(), "text/plain")

        # 4. Возвращаем CDN ссылку
        return JSONResponse({
            "status": "generated",
            "url": f"{CDN_URL}/{report_key}",
            "expires_in": 86400,
            "message": "Report generated and cached"
        })

    except HTTPException:
        raise
    except Exception as e:
        print(f"Error: {e}")
        raise HTTPException(status_code=500, detail=f"Internal error: {str(e)}")

@app.get("/api/reports/health")
async def health():
    return {"status": "ok"}