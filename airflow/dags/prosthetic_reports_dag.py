# Добавить в prosthetic_reports_dag.py

def invalidate_cdn_cache(**context):
    """Инвалидация кэша после обновления данных"""
    import requests

    # Получаем обновлённые протезы
    ti = context['ti']
    updated_prosthetics = ti.xcom_pull(key='updated_prosthetics', task_ids='transform_and_load')

    for prosthetic_id in updated_prosthetics:
        # Вызываем API инвалидации
        requests.post(
            f"http://report-service:8002/api/reports/invalidate/{prosthetic_id}",
            headers={"X-Internal-Token": "etl-secret-token"}
        )

    return len(updated_prosthetics)

# Добавить задачу в DAG
invalidate_cache = PythonOperator(
    task_id='invalidate_cdn_cache',
    python_callable=invalidate_cdn_cache,
    provide_context=True,
)

# Обновить зависимости
transform_load >> invalidate_cache