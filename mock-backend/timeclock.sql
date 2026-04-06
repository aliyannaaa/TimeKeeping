-- --------------------------------------------------------
-- Host:                         127.0.0.1
-- Server version:               10.4.24-MariaDB - mariadb.org binary distribution
-- Server OS:                    Win64
-- HeidiSQL Version:             12.0.0.6468
-- --------------------------------------------------------
Create database local_timeclock;
use local_timeclock;
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

-- Dumping structure for table local_timeclock.employees
CREATE TABLE IF NOT EXISTS `employees` (
  `id` char(50) NOT NULL,
  `employee_id` int(10) NOT NULL,
  `employee_name` varchar(255) DEFAULT NULL,
  `employee_position` varchar(255) DEFAULT NULL,
  `password` varchar(70) DEFAULT NULL,
  `biometric_key` text DEFAULT NULL,
  PRIMARY KEY (`id`,`employee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Data exporting was unselected.

-- Dumping structure for table local_timeclock.history
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

-- Data exporting was unselected.

-- Dumping structure for table local_timeclock.timecard
CREATE TABLE IF NOT EXISTS `timecard` (
  `id` char(32) NOT NULL,
  `time_in` text DEFAULT NULL,
  `time_out` text DEFAULT NULL,
  `employee_id` int(10) NOT NULL,
  `location_time_in` text DEFAULT NULL,
  `location_time_out` text DEFAULT NULL,
  `created_by` varchar(255) DEFAULT NULL,
  `created_date` text DEFAULT NULL,
  `modified_by` varchar(255) DEFAULT NULL,
  `modified_date` text DEFAULT NULL,
  `time_in_type` tinyint(4) DEFAULT NULL,
  `time_out_type` tinyint(4) DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


SELECT
id,
FROM_UNIXTIME(CAST(time_in AS UNSIGNED)/1000) AS time_in_readable,
FROM_UNIXTIME(CAST(time_out AS UNSIGNED)/1000) AS time_out_readable,
FROM_UNIXTIME(CAST(created_date AS UNSIGNED)/1000) AS created_date_readable,
FROM_UNIXTIME(CAST(modified_date AS UNSIGNED)/1000) AS modified_date_readable,
employee_id
FROM timecard;


show tables;
select * from employees;
select * from timecard;
select * from history;

drop database local_timeclock;
-- Data exporting was unselected.



/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;