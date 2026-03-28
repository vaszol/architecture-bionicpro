CREATE TABLE prosthetic_reports (
                                    report_date Date,
                                    prosthetic_id String,
                                    user_id String,
                                    signal_count UInt64,
                                    signal_avg Float32,
                                    response_time_avg Float32,
                                    battery_avg Float32,
                                    performance_score String
) ENGINE = Log