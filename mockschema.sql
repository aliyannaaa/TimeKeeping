CREATE DATABASE IF NOT EXISTS yoshii_db;
USE yoshii_db;

SELECT * FROM users;
SELECT * FROM attendance_logs;

-- Resetting tables to apply new structure
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS attendance_logs;
DROP TABLE IF EXISTS users;
SET FOREIGN_KEY_CHECKS = 1;

-- TABLE 1: Users (Email is the Primary Key)
CREATE TABLE users (
    email VARCHAR(100) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    job_title VARCHAR(100)
);

-- TABLE 2: Attendance Logs (Simplified)
CREATE TABLE attendance_logs (
    id VARCHAR(100) PRIMARY KEY,
    user_email VARCHAR(100), 
    timestamp BIGINT,
    entry_type ENUM('TIME_IN', 'TIME_OUT'),
    status ENUM('SUCCESS', 'FAILED', 'PENDING'),
    FOREIGN KEY (user_email) REFERENCES users(email)
);

-- Insert your OJT test 
INSERT INTO users (email, name, password, job_title) 
VALUES 
('gerard@yoshii', 'Gerard Mamon', 'password123', 'UI/UX Designer'),
('admin@yoshii', 'Admin', 'password123', 'UI/UX Designer'),
('aliyanna@yoshii', 'Kyla Alianna', 'admin2026', 'Wishzen');