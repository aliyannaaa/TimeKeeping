-- --------------------------------------------------------
-- Host:                         127.0.0.1
-- Server version:               10.4.24-MariaDB - mariadb.org binary distribution
-- Server OS:                    Win64
-- HeidiSQL Version:             12.0.0.6468
-- --------------------------------------------------------
CREATE DATABASE IF NOT EXISTS timeclock;
USE timeclock;
drop database timeclock;
select * from employees;
select * from history;
select * from timecard;

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

-- --------------------------------------------------------
-- Base tables
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `employees` (
  `id` char(50) NOT NULL,
  `employee_id` int(10) NOT NULL,
  `employee_name` varchar(255) DEFAULT NULL,
  `employee_position` varchar(255) DEFAULT NULL,
  `login_identifier` varchar(50) DEFAULT NULL,
  `password` varchar(70) DEFAULT NULL,
  `biometric_key` text DEFAULT NULL,
  `biometric_platform` varchar(20) DEFAULT NULL,
  `biometric_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `biometric_updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`,`employee_id`),
  UNIQUE KEY `uk_employees_employee_id` (`employee_id`),
  UNIQUE KEY `uk_employees_login_identifier` (`login_identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Migration: credential + biometric columns for PIN/biometric login flow.
SET @ddl_add_login_identifier = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'login_identifier'
    ),
    'SELECT 1',
    'ALTER TABLE `employees` ADD COLUMN `login_identifier` varchar(50) DEFAULT NULL AFTER `employee_position`'
  )
);
PREPARE stmt_add_login_identifier FROM @ddl_add_login_identifier;
EXECUTE stmt_add_login_identifier;
DEALLOCATE PREPARE stmt_add_login_identifier;

SET @ddl_backfill_login_identifier = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'login_identifier'
    ),
    'UPDATE `employees`
      SET `login_identifier` = LOWER(TRIM(COALESCE(
        CASE WHEN `login_identifier` IS NOT NULL AND LENGTH(TRIM(`login_identifier`)) > 0 THEN `login_identifier` ELSE NULL END,
        CASE WHEN `biometric_key` IS NOT NULL AND TRIM(`biometric_key`) LIKE ''%@%'' THEN TRIM(`biometric_key`) ELSE NULL END,
        CASE WHEN `id` IS NOT NULL AND TRIM(`id`) LIKE ''%@%'' THEN TRIM(`id`) ELSE NULL END,
        CASE WHEN `employee_name` IS NOT NULL AND TRIM(`employee_name`) LIKE ''%@%'' THEN TRIM(`employee_name`) ELSE NULL END
      )))
      WHERE `login_identifier` IS NULL OR LENGTH(TRIM(`login_identifier`)) = 0',
    'SELECT 1'
  )
);
PREPARE stmt_backfill_login_identifier FROM @ddl_backfill_login_identifier;
EXECUTE stmt_backfill_login_identifier;
DEALLOCATE PREPARE stmt_backfill_login_identifier;

SET @ddl_add_biometric_platform = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'biometric_platform'
    ),
    'SELECT 1',
    'ALTER TABLE `employees` ADD COLUMN `biometric_platform` varchar(20) DEFAULT NULL AFTER `biometric_key`'
  )
);
PREPARE stmt_add_biometric_platform FROM @ddl_add_biometric_platform;
EXECUTE stmt_add_biometric_platform;
DEALLOCATE PREPARE stmt_add_biometric_platform;

SET @ddl_add_biometric_enabled = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'biometric_enabled'
    ),
    'SELECT 1',
    'ALTER TABLE `employees` ADD COLUMN `biometric_enabled` tinyint(1) NOT NULL DEFAULT 0 AFTER `biometric_platform`'
  )
);
PREPARE stmt_add_biometric_enabled FROM @ddl_add_biometric_enabled;
EXECUTE stmt_add_biometric_enabled;
DEALLOCATE PREPARE stmt_add_biometric_enabled;

SET @ddl_add_biometric_updated_at = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'biometric_updated_at'
    ),
    'SELECT 1',
    'ALTER TABLE `employees` ADD COLUMN `biometric_updated_at` datetime DEFAULT NULL AFTER `biometric_enabled`'
  )
);
PREPARE stmt_add_biometric_updated_at FROM @ddl_add_biometric_updated_at;
EXECUTE stmt_add_biometric_updated_at;
DEALLOCATE PREPARE stmt_add_biometric_updated_at;

SET @ddl_add_uk_login_identifier = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND INDEX_NAME = 'uk_employees_login_identifier'
    ),
    'SELECT 1',
    'ALTER TABLE `employees` ADD UNIQUE INDEX `uk_employees_login_identifier` (`login_identifier`)'
  )
);
PREPARE stmt_add_uk_login_identifier FROM @ddl_add_uk_login_identifier;
EXECUTE stmt_add_uk_login_identifier;
DEALLOCATE PREPARE stmt_add_uk_login_identifier;

-- Backfill biometric_key from biometric_login_key when biometric_key is empty.
SET @ddl_backfill_biometric_key_from_login_key = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'biometric_key'
    ) AND EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'biometric_login_key'
    ),
    'UPDATE `employees`
      SET `biometric_key` = `biometric_login_key`
      WHERE `biometric_login_key` IS NOT NULL
        AND LENGTH(TRIM(`biometric_login_key`)) > 0
        AND (`biometric_key` IS NULL OR LENGTH(TRIM(`biometric_key`)) = 0)',
    'SELECT 1'
  )
);
PREPARE stmt_backfill_biometric_key_from_login_key FROM @ddl_backfill_biometric_key_from_login_key;
EXECUTE stmt_backfill_biometric_key_from_login_key;
DEALLOCATE PREPARE stmt_backfill_biometric_key_from_login_key;

-- Remove biometric_login_key index/column after backfill so biometric_key is single source of truth.
SET @ddl_drop_uk_biometric_login_key = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND INDEX_NAME = 'uk_employees_biometric_login_key'
    ),
    'ALTER TABLE `employees` DROP INDEX `uk_employees_biometric_login_key`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_uk_biometric_login_key FROM @ddl_drop_uk_biometric_login_key;
EXECUTE stmt_drop_uk_biometric_login_key;
DEALLOCATE PREPARE stmt_drop_uk_biometric_login_key;

SET @ddl_drop_biometric_login_key_column = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employees' AND COLUMN_NAME = 'biometric_login_key'
    ),
    'ALTER TABLE `employees` DROP COLUMN `biometric_login_key`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_biometric_login_key_column FROM @ddl_drop_biometric_login_key_column;
EXECUTE stmt_drop_biometric_login_key_column;
DEALLOCATE PREPARE stmt_drop_biometric_login_key_column;

CREATE TABLE IF NOT EXISTS `history` (
  `id` char(50) CHARACTER SET utf8 NOT NULL,
  `entity_id` char(50) CHARACTER SET utf8 DEFAULT NULL,
  `type` smallint(6) DEFAULT NULL,
  `ip` varchar(25) CHARACTER SET utf8 DEFAULT NULL,
  `title` text CHARACTER SET utf8 DEFAULT NULL,
  `details` text CHARACTER SET utf8 DEFAULT NULL,
  `action` smallint(6) DEFAULT NULL,
  `created_by` char(50) CHARACTER SET utf8 NOT NULL,
  `created_date` text NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `timecard` (
  `id` char(32) NOT NULL,
  `employee_id` int(10) NOT NULL,
  `time_in` datetime NOT NULL COMMENT 'Event timestamp (readable datetime)',
  `location_time_in` varchar(255) DEFAULT NULL,
  `auth_method` varchar(32) DEFAULT NULL,
  `device_name` varchar(255) DEFAULT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `created_by` varchar(255) DEFAULT NULL,
  `created_date` datetime DEFAULT NULL,
  `modified_by` varchar(255) DEFAULT NULL,
  `modified_date` datetime DEFAULT NULL,
  `time_in_type` tinyint(4) unsigned NOT NULL COMMENT '1=time in, 2=time out, 3=overtime in, 4=overtime out',
  `work_date` date GENERATED ALWAYS AS (DATE(`time_in`)) STORED,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_timecard_employee_time` (`employee_id`,`time_in`),
  KEY `idx_timecard_work_date` (`work_date`),
  UNIQUE KEY `uq_timecard_employee_day_type` (`employee_id`,`work_date`,`time_in_type`),
  CONSTRAINT `chk_timecard_type` CHECK (`time_in_type` IN (1,2,3,4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Migration: old unix-millisecond columns -> single datetime columns.
-- Drop generated/date indexes first so time_in can be migrated safely.
SET @ddl_drop_unique_work_type = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'uq_timecard_employee_day_type'
    ),
    'ALTER TABLE `timecard` DROP INDEX `uq_timecard_employee_day_type`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_unique_work_type FROM @ddl_drop_unique_work_type;
EXECUTE stmt_drop_unique_work_type;
DEALLOCATE PREPARE stmt_drop_unique_work_type;

SET @ddl_drop_idx_work_date = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'idx_timecard_work_date'
    ),
    'ALTER TABLE `timecard` DROP INDEX `idx_timecard_work_date`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_idx_work_date FROM @ddl_drop_idx_work_date;
EXECUTE stmt_drop_idx_work_date;
DEALLOCATE PREPARE stmt_drop_idx_work_date;

SET @ddl_drop_work_date = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'work_date'
    ),
    'ALTER TABLE `timecard` DROP COLUMN `work_date`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_work_date FROM @ddl_drop_work_date;
EXECUTE stmt_drop_work_date;
DEALLOCATE PREPARE stmt_drop_work_date;

SET @needs_time_in_migration = (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'timecard'
        AND COLUMN_NAME = 'time_in'
        AND DATA_TYPE <> 'datetime'
    ),
    1,
    0
  )
);

SET @ddl_add_time_in_tmp = IF(
  @needs_time_in_migration = 1
  AND NOT EXISTS(
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'timecard'
      AND COLUMN_NAME = 'time_in_tmp'
  ),
  'ALTER TABLE `timecard` ADD COLUMN `time_in_tmp` datetime NULL',
  'SELECT 1'
);
PREPARE stmt_add_time_in_tmp FROM @ddl_add_time_in_tmp;
EXECUTE stmt_add_time_in_tmp;
DEALLOCATE PREPARE stmt_add_time_in_tmp;

SET @ddl_fill_time_in_tmp = IF(
  @needs_time_in_migration = 1,
  'UPDATE `timecard` SET `time_in_tmp` = FROM_UNIXTIME(`time_in` / 1000)',
  'SELECT 1'
);
PREPARE stmt_fill_time_in_tmp FROM @ddl_fill_time_in_tmp;
EXECUTE stmt_fill_time_in_tmp;
DEALLOCATE PREPARE stmt_fill_time_in_tmp;

SET @ddl_drop_time_in = IF(
  @needs_time_in_migration = 1,
  'ALTER TABLE `timecard` DROP COLUMN `time_in`',
  'SELECT 1'
);
PREPARE stmt_drop_time_in FROM @ddl_drop_time_in;
EXECUTE stmt_drop_time_in;
DEALLOCATE PREPARE stmt_drop_time_in;

SET @ddl_rename_time_in_tmp = IF(
  @needs_time_in_migration = 1,
  'ALTER TABLE `timecard` CHANGE COLUMN `time_in_tmp` `time_in` datetime NOT NULL COMMENT ''Event timestamp (readable datetime)''',
  'SELECT 1'
);
PREPARE stmt_rename_time_in_tmp FROM @ddl_rename_time_in_tmp;
EXECUTE stmt_rename_time_in_tmp;
DEALLOCATE PREPARE stmt_rename_time_in_tmp;

SET @needs_created_date_migration = (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'timecard'
        AND COLUMN_NAME = 'created_date'
        AND DATA_TYPE <> 'datetime'
    ),
    1,
    0
  )
);

SET @ddl_add_created_date_tmp = IF(
  @needs_created_date_migration = 1
  AND NOT EXISTS(
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'timecard'
      AND COLUMN_NAME = 'created_date_tmp'
  ),
  'ALTER TABLE `timecard` ADD COLUMN `created_date_tmp` datetime NULL',
  'SELECT 1'
);
PREPARE stmt_add_created_date_tmp FROM @ddl_add_created_date_tmp;
EXECUTE stmt_add_created_date_tmp;
DEALLOCATE PREPARE stmt_add_created_date_tmp;

SET @ddl_fill_created_date_tmp = IF(
  @needs_created_date_migration = 1,
  'UPDATE `timecard` SET `created_date_tmp` = CASE WHEN `created_date` IS NULL OR `created_date` = 0 THEN NULL ELSE FROM_UNIXTIME(`created_date` / 1000) END',
  'SELECT 1'
);
PREPARE stmt_fill_created_date_tmp FROM @ddl_fill_created_date_tmp;
EXECUTE stmt_fill_created_date_tmp;
DEALLOCATE PREPARE stmt_fill_created_date_tmp;

SET @ddl_drop_created_date = IF(
  @needs_created_date_migration = 1,
  'ALTER TABLE `timecard` DROP COLUMN `created_date`',
  'SELECT 1'
);
PREPARE stmt_drop_created_date FROM @ddl_drop_created_date;
EXECUTE stmt_drop_created_date;
DEALLOCATE PREPARE stmt_drop_created_date;

SET @ddl_rename_created_date_tmp = IF(
  @needs_created_date_migration = 1,
  'ALTER TABLE `timecard` CHANGE COLUMN `created_date_tmp` `created_date` datetime NULL',
  'SELECT 1'
);
PREPARE stmt_rename_created_date_tmp FROM @ddl_rename_created_date_tmp;
EXECUTE stmt_rename_created_date_tmp;
DEALLOCATE PREPARE stmt_rename_created_date_tmp;

SET @needs_modified_date_migration = (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'timecard'
        AND COLUMN_NAME = 'modified_date'
        AND DATA_TYPE <> 'datetime'
    ),
    1,
    0
  )
);

SET @ddl_add_modified_date_tmp = IF(
  @needs_modified_date_migration = 1
  AND NOT EXISTS(
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'timecard'
      AND COLUMN_NAME = 'modified_date_tmp'
  ),
  'ALTER TABLE `timecard` ADD COLUMN `modified_date_tmp` datetime NULL',
  'SELECT 1'
);
PREPARE stmt_add_modified_date_tmp FROM @ddl_add_modified_date_tmp;
EXECUTE stmt_add_modified_date_tmp;
DEALLOCATE PREPARE stmt_add_modified_date_tmp;

SET @ddl_fill_modified_date_tmp = IF(
  @needs_modified_date_migration = 1,
  'UPDATE `timecard` SET `modified_date_tmp` = CASE WHEN `modified_date` IS NULL OR `modified_date` = 0 THEN NULL ELSE FROM_UNIXTIME(`modified_date` / 1000) END',
  'SELECT 1'
);
PREPARE stmt_fill_modified_date_tmp FROM @ddl_fill_modified_date_tmp;
EXECUTE stmt_fill_modified_date_tmp;
DEALLOCATE PREPARE stmt_fill_modified_date_tmp;

SET @ddl_drop_modified_date = IF(
  @needs_modified_date_migration = 1,
  'ALTER TABLE `timecard` DROP COLUMN `modified_date`',
  'SELECT 1'
);
PREPARE stmt_drop_modified_date FROM @ddl_drop_modified_date;
EXECUTE stmt_drop_modified_date;
DEALLOCATE PREPARE stmt_drop_modified_date;

SET @ddl_rename_modified_date_tmp = IF(
  @needs_modified_date_migration = 1,
  'ALTER TABLE `timecard` CHANGE COLUMN `modified_date_tmp` `modified_date` datetime NULL',
  'SELECT 1'
);
PREPARE stmt_rename_modified_date_tmp FROM @ddl_rename_modified_date_tmp;
EXECUTE stmt_rename_modified_date_tmp;
DEALLOCATE PREPARE stmt_rename_modified_date_tmp;

SET @ddl_drop_time_in_readable = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'time_in_readable'
    ),
    'ALTER TABLE `timecard` DROP COLUMN `time_in_readable`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_time_in_readable FROM @ddl_drop_time_in_readable;
EXECUTE stmt_drop_time_in_readable;
DEALLOCATE PREPARE stmt_drop_time_in_readable;

SET @ddl_drop_event_datetime = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'event_datetime'
    ),
    'ALTER TABLE `timecard` DROP COLUMN `event_datetime`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_event_datetime FROM @ddl_drop_event_datetime;
EXECUTE stmt_drop_event_datetime;
DEALLOCATE PREPARE stmt_drop_event_datetime;

SET @ddl_drop_created_datetime = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'created_datetime'
    ),
    'ALTER TABLE `timecard` DROP COLUMN `created_datetime`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_created_datetime FROM @ddl_drop_created_datetime;
EXECUTE stmt_drop_created_datetime;
DEALLOCATE PREPARE stmt_drop_created_datetime;

SET @ddl_drop_modified_datetime = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'modified_datetime'
    ),
    'ALTER TABLE `timecard` DROP COLUMN `modified_datetime`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_modified_datetime FROM @ddl_drop_modified_datetime;
EXECUTE stmt_drop_modified_datetime;
DEALLOCATE PREPARE stmt_drop_modified_datetime;

SET @ddl_drop_unique_work_type = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'uq_timecard_employee_day_type'
    ),
    'ALTER TABLE `timecard` DROP INDEX `uq_timecard_employee_day_type`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_unique_work_type FROM @ddl_drop_unique_work_type;
EXECUTE stmt_drop_unique_work_type;
DEALLOCATE PREPARE stmt_drop_unique_work_type;

SET @ddl_drop_idx_work_date = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'idx_timecard_work_date'
    ),
    'ALTER TABLE `timecard` DROP INDEX `idx_timecard_work_date`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_idx_work_date FROM @ddl_drop_idx_work_date;
EXECUTE stmt_drop_idx_work_date;
DEALLOCATE PREPARE stmt_drop_idx_work_date;

SET @ddl_drop_work_date = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'work_date'
    ),
    'ALTER TABLE `timecard` DROP COLUMN `work_date`',
    'SELECT 1'
  )
);
PREPARE stmt_drop_work_date FROM @ddl_drop_work_date;
EXECUTE stmt_drop_work_date;
DEALLOCATE PREPARE stmt_drop_work_date;

SET @ddl_add_work_date = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'work_date'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD COLUMN `work_date` date GENERATED ALWAYS AS (DATE(`time_in`)) STORED'
  )
);
PREPARE stmt_add_work_date FROM @ddl_add_work_date;
EXECUTE stmt_add_work_date;
DEALLOCATE PREPARE stmt_add_work_date;

SET @ddl_add_idx_employee_time = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'idx_timecard_employee_time'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD INDEX `idx_timecard_employee_time` (`employee_id`,`time_in`)'
  )
);
PREPARE stmt_add_idx_employee_time FROM @ddl_add_idx_employee_time;
EXECUTE stmt_add_idx_employee_time;
DEALLOCATE PREPARE stmt_add_idx_employee_time;

SET @ddl_add_idx_work_date = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'idx_timecard_work_date'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD INDEX `idx_timecard_work_date` (`work_date`)'
  )
);
PREPARE stmt_add_idx_work_date FROM @ddl_add_idx_work_date;
EXECUTE stmt_add_idx_work_date;
DEALLOCATE PREPARE stmt_add_idx_work_date;

SET @ddl_add_unique_work_type = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND INDEX_NAME = 'uq_timecard_employee_day_type'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD UNIQUE INDEX `uq_timecard_employee_day_type` (`employee_id`,`work_date`,`time_in_type`)'
  )
);
PREPARE stmt_add_unique_work_type FROM @ddl_add_unique_work_type;
EXECUTE stmt_add_unique_work_type;
DEALLOCATE PREPARE stmt_add_unique_work_type;

-- Migration: auth metadata for time in/out events.
SET @ddl_add_auth_method = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'auth_method'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD COLUMN `auth_method` varchar(32) DEFAULT NULL AFTER `location_time_in`'
  )
);
PREPARE stmt_add_auth_method FROM @ddl_add_auth_method;
EXECUTE stmt_add_auth_method;
DEALLOCATE PREPARE stmt_add_auth_method;

SET @ddl_add_device_name = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'device_name'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD COLUMN `device_name` varchar(255) DEFAULT NULL AFTER `auth_method`'
  )
);
PREPARE stmt_add_device_name FROM @ddl_add_device_name;
EXECUTE stmt_add_device_name;
DEALLOCATE PREPARE stmt_add_device_name;

SET @ddl_add_latitude = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'latitude'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD COLUMN `latitude` decimal(10,7) DEFAULT NULL AFTER `device_name`'
  )
);
PREPARE stmt_add_latitude FROM @ddl_add_latitude;
EXECUTE stmt_add_latitude;
DEALLOCATE PREPARE stmt_add_latitude;

SET @ddl_add_longitude = (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timecard' AND COLUMN_NAME = 'longitude'
    ),
    'SELECT 1',
    'ALTER TABLE `timecard` ADD COLUMN `longitude` decimal(10,7) DEFAULT NULL AFTER `latitude`'
  )
);
PREPARE stmt_add_longitude FROM @ddl_add_longitude;
EXECUTE stmt_add_longitude;
DEALLOCATE PREPARE stmt_add_longitude;

SHOW TABLES;

SELECT * FROM employees;
SELECT * FROM timecard;
SELECT * FROM history;
SELECT
  id,
  employee_id,
  time_in_type,
  time_in AS event_time,
  location_time_in,
  auth_method,
  device_name,
  latitude,
  longitude,
  created_date,
  modified_date,
  work_date
FROM timecard
ORDER BY employee_id, time_in;

-- Daily limits by type (should be max 1 row per type/day due to unique key)
SELECT
  employee_id,
  work_date,
  SUM(time_in_type = 1) AS time_in_rows,
  SUM(time_in_type = 2) AS time_out_rows,
  SUM(time_in_type = 3) AS overtime_in_rows,
  SUM(time_in_type = 4) AS overtime_out_rows
FROM timecard
GROUP BY employee_id, work_date;

/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;