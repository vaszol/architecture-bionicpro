-- Создание таблицы CRM
CREATE TABLE IF NOT EXISTS prosthetic_orders (
                                                 id VARCHAR(50) PRIMARY KEY,
                                                 user_id VARCHAR(50),
                                                 prosthetic_id VARCHAR(50),
                                                 prosthetic_type VARCHAR(100),
                                                 purchase_date DATE,
                                                 updated_at TIMESTAMP DEFAULT NOW()
);

-- Создание таблицы телеметрии
CREATE TABLE IF NOT EXISTS prosthetic_telemetry (
                                                    id SERIAL PRIMARY KEY,
                                                    prosthetic_id VARCHAR(50),
                                                    timestamp TIMESTAMP DEFAULT NOW(),
                                                    signal_strength FLOAT,
                                                    response_time_ms FLOAT,
                                                    battery_level FLOAT
);

-- Добавление тестовых данных (только если таблицы пустые)
INSERT INTO prosthetic_orders (id, user_id, prosthetic_id, prosthetic_type, purchase_date)
SELECT * FROM (VALUES
                   ('1', 'user_1', 'prosthetic_1', 'bionic_hand', '2024-01-01'::DATE),
                   ('2', 'user_2', 'prosthetic_2', 'bionic_arm', '2024-01-02'::DATE),
                   ('3', 'user_3', 'prosthetic_3', 'bionic_leg', '2024-01-03'::DATE)
              ) AS v(id, user_id, prosthetic_id, prosthetic_type, purchase_date)
WHERE NOT EXISTS (SELECT 1 FROM prosthetic_orders LIMIT 1);

INSERT INTO prosthetic_telemetry (prosthetic_id, signal_strength, response_time_ms, battery_level)
SELECT * FROM (VALUES
                   ('prosthetic_1', 85, 95, 75),
                   ('prosthetic_1', 82, 102, 73),
                   ('prosthetic_1', 78, 110, 70),
                   ('prosthetic_2', 78, 150, 68),
                   ('prosthetic_2', 75, 165, 65),
                   ('prosthetic_3', 92, 75, 82),
                   ('prosthetic_3', 90, 78, 80)
              ) AS v(prosthetic_id, signal_strength, response_time_ms, battery_level)
WHERE NOT EXISTS (SELECT 1 FROM prosthetic_telemetry LIMIT 1);