// Mock backend API for Timekeeper app (MariaDB/MySQL)
const crypto = require('crypto');
const express = require('express');
const mysql = require('mysql2/promise');

const app = express();
const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';

app.use(express.json());

const dbPool = mysql.createPool({
  host: process.env.DB_HOST || 'localhost',
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || 'Root123456789',
  database: process.env.DB_NAME || 'timeclock',
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
});

function normalizeBiometricPlatform(value) {
  const normalized = String(value || '').trim().toLowerCase();
  if (!normalized) {
    return null;
  }

  if (normalized !== 'android' && normalized !== 'ios') {
    throw new Error('platform must be android or ios');
  }

  return normalized;
}

function normalizeBiometricKey(value) {
  const normalized = String(value || '').trim();
  if (!normalized) {
    throw new Error('biometric_key is required');
  }

  if (normalized.length < 16 || normalized.length > 255) {
    throw new Error('biometric_key must be between 16 and 255 characters');
  }

  return normalized;
}

function normalizeAuthMethod(value) {
  const normalized = String(value || '').trim().toLowerCase();
  if (!normalized) {
    return null;
  }

  if (!['pin', 'password', 'biometric_key'].includes(normalized)) {
    throw new Error('auth_method must be pin, password, or biometric_key');
  }

  return normalized;
}

function normalizeDeviceName(value) {
  const normalized = String(value || '').trim();
  if (!normalized) {
    return null;
  }

  if (normalized.length > 255) {
    throw new Error('device_name must be 255 characters or fewer');
  }

  return normalized;
}

function normalizeCoordinate(value, fieldName) {
  if (value === null || value === undefined || value === '') {
    return null;
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${fieldName} must be a valid number`);
  }

  if (fieldName === 'latitude' && (parsed < -90 || parsed > 90)) {
    throw new Error('latitude must be between -90 and 90');
  }

  if (fieldName === 'longitude' && (parsed < -180 || parsed > 180)) {
    throw new Error('longitude must be between -180 and 180');
  }

  return parsed;
}

async function getEmployeeCapabilities() {
  const [columns] = await dbPool.query('SHOW COLUMNS FROM employees');
  const fields = new Set(columns.map((column) => column.Field));
  const hasColumn = (columnName) => fields.has(columnName);

  return {
    columns,
    hasColumn,
    hasId: hasColumn('id'),
    hasEmployeeId: hasColumn('employee_id'),
    hasEmployeeName: hasColumn('employee_name'),
    hasEmployeePosition: hasColumn('employee_position'),
    hasPassword: hasColumn('password'),
    hasBiometricKey: hasColumn('biometric_key'),
    hasLoginIdentifier: hasColumn('login_identifier'),
    hasBiometricPlatform: hasColumn('biometric_platform'),
    hasBiometricEnabled: hasColumn('biometric_enabled'),
    hasBiometricUpdatedAt: hasColumn('biometric_updated_at')
  };
}

function buildSelectCredentialColumns(capabilities) {
  const usernameCandidates = [];
  if (capabilities.hasLoginIdentifier) {
    usernameCandidates.push("NULLIF(LOWER(TRIM(emp.login_identifier)), '')");
  }
  if (!capabilities.hasLoginIdentifier && capabilities.hasBiometricKey) {
    usernameCandidates.push("NULLIF(LOWER(TRIM(emp.biometric_key)), '')");
  }
  if (capabilities.hasId) {
    usernameCandidates.push("CASE WHEN emp.id LIKE '%@%' THEN LOWER(TRIM(emp.id)) ELSE NULL END");
  }
  if (capabilities.hasEmployeeName) {
    usernameCandidates.push("CASE WHEN emp.employee_name LIKE '%@%' THEN LOWER(TRIM(emp.employee_name)) ELSE NULL END");
    usernameCandidates.push("NULLIF(LOWER(TRIM(emp.employee_name)), '')");
  }

  const usernameExpr = usernameCandidates.length > 0
    ? `COALESCE(${usernameCandidates.join(', ')}, CONCAT('employee_', CAST(emp.employee_id AS CHAR)))`
    : `CONCAT('employee_', CAST(emp.employee_id AS CHAR))`;

  const fullNameExpr = capabilities.hasEmployeeName
    ? `
      CASE
        WHEN emp.employee_name IS NULL OR TRIM(emp.employee_name) = '' THEN 'Unknown User'
        WHEN emp.employee_name LIKE '%@%' THEN SUBSTRING_INDEX(TRIM(emp.employee_name), '@', 1)
        ELSE TRIM(emp.employee_name)
      END
    `
    : `'Unknown User'`;

  const employeePositionExpr = capabilities.hasEmployeePosition
    ? `NULLIF(TRIM(emp.employee_position), '')`
    : 'NULL';

  const emailCandidates = [];
  if (capabilities.hasLoginIdentifier) {
    emailCandidates.push("CASE WHEN emp.login_identifier IS NOT NULL AND TRIM(emp.login_identifier) LIKE '%@%' THEN LOWER(TRIM(emp.login_identifier)) ELSE NULL END");
  }
  if (!capabilities.hasLoginIdentifier && capabilities.hasBiometricKey) {
    emailCandidates.push("CASE WHEN emp.biometric_key IS NOT NULL AND TRIM(emp.biometric_key) LIKE '%@%' THEN LOWER(TRIM(emp.biometric_key)) ELSE NULL END");
  }
  if (capabilities.hasId) {
    emailCandidates.push("CASE WHEN emp.id IS NOT NULL AND TRIM(emp.id) LIKE '%@%' THEN LOWER(TRIM(emp.id)) ELSE NULL END");
  }
  if (capabilities.hasEmployeeName) {
    emailCandidates.push("CASE WHEN emp.employee_name IS NOT NULL AND TRIM(emp.employee_name) LIKE '%@%' THEN LOWER(TRIM(emp.employee_name)) ELSE NULL END");
  }

  const emailExpr = emailCandidates.length > 0
    ? `COALESCE(${emailCandidates.join(', ')})`
    : 'NULL';

  const biometricEnabledExpr = capabilities.hasBiometricEnabled
    ? `CAST(COALESCE(emp.biometric_enabled, 0) AS UNSIGNED)`
    : '0';

  const biometricPlatformExpr = capabilities.hasBiometricPlatform
    ? `NULLIF(TRIM(emp.biometric_platform), '')`
    : 'NULL';

  const biometricUpdatedAtExpr = capabilities.hasBiometricUpdatedAt
    ? 'emp.biometric_updated_at'
    : 'NULL';

  const passwordExpr = capabilities.hasPassword ? 'emp.password' : 'NULL';

  return `
    SELECT
      emp.employee_id AS id,
      ${usernameExpr} AS username,
      ${passwordExpr} AS password,
      ${fullNameExpr} AS full_name,
      ${employeePositionExpr} AS employee_position,
      ${emailExpr} AS email,
      ${biometricEnabledExpr} AS biometric_enabled,
      ${biometricPlatformExpr} AS biometric_platform,
      ${biometricUpdatedAtExpr} AS biometric_updated_at
    FROM employees AS emp
  `;
}

function buildSelectTimecardColumns(capabilities) {
  return `
    SELECT
      ${capabilities.hasRecordNo ? 'CAST(tc.record_no AS CHAR)' : 'tc.id'} AS id,
      tc.employee_id AS user_id,
      tc.time_in AS entry_time,
      tc.time_in_type AS entry_type,
      tc.location_time_in AS location_time_in,
      ${capabilities.hasAuthMethod ? 'tc.auth_method' : 'NULL'} AS auth_method,
      ${capabilities.hasDeviceName ? 'tc.device_name' : 'NULL'} AS device_name,
      ${capabilities.hasLatitude ? 'tc.latitude' : 'NULL'} AS latitude,
      ${capabilities.hasLongitude ? 'tc.longitude' : 'NULL'} AS longitude,
      tc.created_date AS created_date,
      tc.modified_date AS modified_date
    FROM timecard AS tc
  `;
}

const ENTRY_TYPES = Object.freeze({
  TIME_IN: 1,
  TIME_OUT: 2,
  OVERTIME_IN: 3,
  OVERTIME_OUT: 4
});

function dbErrorToHttp(err) {
  if (err && err.code === 'ER_DUP_ENTRY') {
    return { status: 409, body: { error: 'Duplicate value violates unique constraint', detail: err.sqlMessage } };
  }

  if (err && (err.code === 'ER_NO_REFERENCED_ROW_2' || err.code === 'ER_ROW_IS_REFERENCED_2')) {
    return { status: 400, body: { error: 'Foreign key constraint violation', detail: err.sqlMessage } };
  }

  if (err && err.code === 'ER_CHECK_CONSTRAINT_VIOLATED') {
    return { status: 400, body: { error: 'Check constraint violation', detail: err.sqlMessage } };
  }

  return { status: 500, body: { error: 'Database error', detail: err ? err.sqlMessage : 'Unknown error' } };
}

function toPositiveInt(value, fieldName) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${fieldName} must be a positive integer`);
  }
  return parsed;
}

function toPositiveTimestamp(value, fieldName) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${fieldName} must be a positive unix timestamp in milliseconds`);
  }
  return parsed;
}

function toOptionalPositiveInt(value, fieldName) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  return toPositiveInt(value, fieldName);
}

function parseTimestampMs(value) {
  if (value === null || value === undefined) {
    return null;
  }

  if (value instanceof Date) {
    const time = value.getTime();
    return Number.isFinite(time) && time > 0 ? time : null;
  }

  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric > 0) {
    return Math.trunc(numeric);
  }

  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed) && parsed > 0) {
      return parsed;
    }
  }

  return null;
}

function formatTimestampReadable(timestampMs) {
  if (!Number.isFinite(timestampMs) || timestampMs <= 0) {
    return null;
  }

  const date = new Date(timestampMs);
  if (Number.isNaN(date.getTime())) {
    return null;
  }

  const pad = (value) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function mapTimecardRow(row) {
  const entryTimeMs = parseTimestampMs(row.entry_time);
  const createdDateMs = parseTimestampMs(row.created_date);
  const modifiedDateMs = parseTimestampMs(row.modified_date);

  return {
    id: String(row.id),
    user_id: Number(row.user_id),
    entry_time: formatTimestampReadable(entryTimeMs),
    entry_time_ms: entryTimeMs,
    entry_type: Number(row.entry_type || 0),
    created_date: formatTimestampReadable(createdDateMs),
    modified_date: formatTimestampReadable(modifiedDateMs),
    created_date_ms: createdDateMs,
    modified_date_ms: modifiedDateMs,
    location_time_in: row.location_time_in || null,
    auth_method: row.auth_method || null,
    device_name: row.device_name || null,
    latitude: row.latitude === null || row.latitude === undefined ? null : Number(row.latitude),
    longitude: row.longitude === null || row.longitude === undefined ? null : Number(row.longitude)
  };
}

function getDayKey(timestampMs) {
  if (!Number.isFinite(timestampMs) || timestampMs <= 0) {
    return null;
  }

  const date = new Date(timestampMs);
  if (Number.isNaN(date.getTime())) {
    return null;
  }

  const pad = (value) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function getAttendanceDayState(records, referenceMs) {
  const targetDay = getDayKey(referenceMs);
  const todaysRecords = records
    .filter((record) => getDayKey(record.entry_time_ms) === targetDay)
    .sort((a, b) => (a.entry_time_ms || 0) - (b.entry_time_ms || 0));

  const latestByType = new Map();
  todaysRecords.forEach((record) => {
    latestByType.set(record.entry_type, record);
  });

  const hasType = (type) => latestByType.has(type);
  const clockedIn = (hasType(ENTRY_TYPES.TIME_IN) && !hasType(ENTRY_TYPES.TIME_OUT))
    || (hasType(ENTRY_TYPES.OVERTIME_IN) && !hasType(ENTRY_TYPES.OVERTIME_OUT));

  return {
    todaysRecords,
    latestByType,
    hasType,
    clockedIn
  };
}

function deriveAttendanceState(dayState) {
  if (dayState.hasType(ENTRY_TYPES.OVERTIME_IN) && !dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)) {
    return 'OVERTIME_IN';
  }

  if (dayState.hasType(ENTRY_TYPES.TIME_IN) && !dayState.hasType(ENTRY_TYPES.TIME_OUT)) {
    return 'NORMAL_IN';
  }

  if (dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)) {
    return 'OVERTIME_OUT';
  }

  if (dayState.hasType(ENTRY_TYPES.TIME_OUT)) {
    return 'NORMAL_OUT';
  }

  if (dayState.hasType(ENTRY_TYPES.TIME_IN)) {
    return 'NORMAL_IN';
  }

  return 'NO_RECORD';
}

function resolveTargetType(action, dayState) {
  if (action === 'in') {
    if (dayState.clockedIn) {
      return {
        targetType: null,
        responseType: dayState.hasType(ENTRY_TYPES.OVERTIME_IN) && !dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)
          ? ENTRY_TYPES.OVERTIME_IN
          : ENTRY_TYPES.TIME_IN,
        forceOverride: false,
        notice: "you're already logged in"
      };
    }

    if (!dayState.hasType(ENTRY_TYPES.TIME_IN)) {
      return {
        targetType: ENTRY_TYPES.TIME_IN,
        forceOverride: false,
        notice: null
      };
    }

    if (dayState.hasType(ENTRY_TYPES.TIME_IN)
      && dayState.hasType(ENTRY_TYPES.TIME_OUT)
      && !dayState.hasType(ENTRY_TYPES.OVERTIME_IN)) {
      return {
        targetType: ENTRY_TYPES.OVERTIME_IN,
        responseType: ENTRY_TYPES.OVERTIME_IN,
        forceOverride: false,
        notice: null
      };
    }

    if (dayState.hasType(ENTRY_TYPES.OVERTIME_IN) && dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)) {
      return {
        targetType: null,
        responseType: ENTRY_TYPES.OVERTIME_OUT,
        forceOverride: false,
        notice: 'attendance already completed for today'
      };
    }

    return {
      targetType: null,
      responseType: dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)
        ? ENTRY_TYPES.OVERTIME_OUT
        : ENTRY_TYPES.TIME_OUT,
      forceOverride: false,
      notice: "you're already logged in"
    };
  }

  if (action === 'out') {
    if (dayState.clockedIn) {
      const targetType = dayState.hasType(ENTRY_TYPES.OVERTIME_IN) && !dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)
        ? ENTRY_TYPES.OVERTIME_OUT
        : ENTRY_TYPES.TIME_OUT;
      return {
        targetType,
        responseType: targetType,
        forceOverride: false,
        notice: null
      };
    }

    if (!dayState.hasType(ENTRY_TYPES.TIME_IN)) {
      return {
        targetType: null,
        forceOverride: false,
        notice: null,
        error: 'Must log in before log out'
      };
    }

    const responseType = dayState.hasType(ENTRY_TYPES.OVERTIME_OUT)
      ? ENTRY_TYPES.OVERTIME_OUT
      : ENTRY_TYPES.TIME_OUT;

    return {
      targetType: null,
      responseType,
      forceOverride: false,
      notice: "you're already logged out"
    };
  }

  return {
    targetType: null,
    forceOverride: false,
    notice: null,
    error: 'Invalid action. Allowed values: in, out'
  };
}

function getRecordWhereClause(hasRecordNo, recordId) {
  if (hasRecordNo) {
    return {
      clause: 'tc.record_no = ?',
      value: toPositiveInt(recordId, 'id')
    };
  }

  return {
    clause: 'tc.id = ?',
    value: String(recordId)
  };
}

async function loadUserMappedRecords(selectTimecardColumns, userId) {
  const [rows] = await dbPool.query(
    `${selectTimecardColumns} WHERE tc.employee_id = ? ORDER BY tc.time_in ASC`,
    [userId]
  );
  return rows.map(mapTimecardRow);
}

async function getEmployeeIdentity(employeeId) {
  const [rows] = await dbPool.query(
    'SELECT employee_id, id FROM employees WHERE employee_id = ? LIMIT 1',
    [employeeId]
  );

  if (!rows || rows.length === 0) {
    return null;
  }

  return {
    employeeId: Number(rows[0].employee_id),
    id: rows[0].id ? String(rows[0].id).trim() : ''
  };
}

async function resolveActorIdentifier(employeeCapabilities, actorUserId, fallbackUserId) {
  const resolvedActorUserId = actorUserId || fallbackUserId;

  if (!employeeCapabilities.hasId) {
    return `employee:${resolvedActorUserId}`;
  }

  try {
    const actorIdentity = await getEmployeeIdentity(resolvedActorUserId);
    const actorId = actorIdentity && actorIdentity.id ? actorIdentity.id : '';
    return actorId || `employee:${resolvedActorUserId}`;
  } catch (_) {
    return `employee:${resolvedActorUserId}`;
  }
}

async function createOrOverrideAttendanceEntry({
  timecardCapabilities,
  employeeCapabilities,
  userId,
  actorUserId,
  eventTime,
  action,
  locationTimeIn,
  authMethod,
  deviceName,
  latitude,
  longitude
}) {
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities);

  const targetEmployee = await getEmployeeIdentity(userId);
  if (!targetEmployee) {
    return {
      statusCode: 404,
      body: { error: `Target employee not found for user_id=${userId}` }
    };
  }

  if (actorUserId !== null && actorUserId !== undefined) {
    const actorEmployee = await getEmployeeIdentity(actorUserId);
    if (!actorEmployee) {
      return {
        statusCode: 404,
        body: { error: `Actor employee not found for actor_user_id=${actorUserId}` }
      };
    }
  }

  const mappedRecords = await loadUserMappedRecords(selectTimecardColumns, userId);
  const dayState = getAttendanceDayState(mappedRecords, eventTime);
  const decision = resolveTargetType(action, dayState);

  if (decision.error) {
    return {
      statusCode: 400,
      body: { error: decision.error }
    };
  }

  if (!decision.targetType) {
    const fallbackRecord = decision.responseType
      ? dayState.latestByType.get(decision.responseType)
      : dayState.todaysRecords[dayState.todaysRecords.length - 1];

    if (!fallbackRecord) {
      return {
        statusCode: 409,
        body: {
          error: decision.notice || 'Unable to process attendance entry with current state'
        }
      };
    }

    return {
      statusCode: 200,
      body: {
        record: fallbackRecord,
        overridden: false,
        entry_created: false,
        notice: decision.notice,
        clocked_in: dayState.clockedIn,
        attendance_state: deriveAttendanceState(dayState)
      }
    };
  }

  const existingForType = dayState.latestByType.get(decision.targetType);
  if (existingForType) {
    return {
      statusCode: 200,
      body: {
        record: existingForType,
        overridden: false,
        entry_created: false,
        notice: decision.notice || 'entry already exists for this action',
        clocked_in: dayState.clockedIn,
        attendance_state: deriveAttendanceState(dayState)
      }
    };
  }

  const actor = await resolveActorIdentifier(employeeCapabilities, actorUserId, userId);
  const nowMs = Date.now();
  const timecardUuid = crypto.randomUUID().replace(/-/g, '');
  const insertColumns = [
    'id',
    'employee_id',
    'time_in',
    'location_time_in',
    'created_by',
    'created_date',
    'time_in_type'
  ];
  const insertValues = [
    timecardUuid,
    userId,
    eventTime,
    locationTimeIn,
    actor,
    nowMs,
    decision.targetType
  ];

  if (timecardCapabilities.hasAuthMethod) {
    insertColumns.push('auth_method');
    insertValues.push(authMethod);
  }
  if (timecardCapabilities.hasDeviceName) {
    insertColumns.push('device_name');
    insertValues.push(deviceName);
  }
  if (timecardCapabilities.hasLatitude) {
    insertColumns.push('latitude');
    insertValues.push(latitude);
  }
  if (timecardCapabilities.hasLongitude) {
    insertColumns.push('longitude');
    insertValues.push(longitude);
  }

  const selectExpressions = insertColumns.map((column) => (
    column === 'time_in' || column === 'created_date' ? 'FROM_UNIXTIME(? / 1000)' : '?'
  )).join(', ');
  const gatedInsertValues = [...insertValues, userId];

  const [insertResult] = await dbPool.query(
    `
      INSERT INTO timecard (${insertColumns.join(', ')})
      SELECT ${selectExpressions}
      FROM employees AS emp
      WHERE emp.employee_id = ?
      LIMIT 1
    `,
    gatedInsertValues
  );

  if (!insertResult.affectedRows) {
    return {
      statusCode: 404,
      body: { error: `Target employee not found for user_id=${userId}` }
    };
  }

  const whereClause = timecardCapabilities.hasRecordNo ? 'tc.record_no = ?' : 'tc.id = ?';
  const whereValue = timecardCapabilities.hasRecordNo
    ? Number(insertResult.insertId)
    : timecardUuid;

  const [updatedRows] = await dbPool.query(
    `${selectTimecardColumns} WHERE ${whereClause} LIMIT 1`,
    [whereValue]
  );

  const refreshedRecord = mapTimecardRow(updatedRows[0]);
  const refreshedRecords = await loadUserMappedRecords(selectTimecardColumns, userId);
  const refreshedState = getAttendanceDayState(refreshedRecords, eventTime);

  return {
    statusCode: 201,
    body: {
      record: refreshedRecord,
      overridden: false,
      entry_created: true,
      notice: decision.notice,
      clocked_in: refreshedState.clockedIn,
      attendance_state: deriveAttendanceState(refreshedState)
    }
  }
}

function asyncRoute(handler) {
  return (req, res, next) => {
    Promise.resolve(handler(req, res, next)).catch(next);
  };
}

async function getTimecardCapabilities() {
  const [columns] = await dbPool.query('SHOW COLUMNS FROM timecard');
  const fields = new Set(columns.map((column) => column.Field));
  return {
    hasRecordNo: fields.has('record_no'),
    hasAuthMethod: fields.has('auth_method'),
    hasDeviceName: fields.has('device_name'),
    hasLatitude: fields.has('latitude'),
    hasLongitude: fields.has('longitude')
  };
}

app.get('/credentials', asyncRoute(async (req, res) => {
  const employeeCapabilities = await getEmployeeCapabilities();
  const selectCredentialColumns = buildSelectCredentialColumns(employeeCapabilities);
  const [rows] = await dbPool.query(`${selectCredentialColumns} ORDER BY emp.employee_id ASC`);
  res.json(rows);
}));

app.get('/health', asyncRoute(async (req, res) => {
  await dbPool.query('SELECT 1');
  res.json({ status: 'ok' });
}));

app.get('/credentials/:id', asyncRoute(async (req, res) => {
  const employeeCapabilities = await getEmployeeCapabilities();
  const selectCredentialColumns = buildSelectCredentialColumns(employeeCapabilities);
  const credentialId = toPositiveInt(req.params.id, 'id');
  const [rows] = await dbPool.query(
    `${selectCredentialColumns} WHERE emp.employee_id = ? LIMIT 1`,
    [credentialId]
  );

  if (rows.length === 0) {
    return res.status(404).json({ error: 'User not found' });
  }

  res.json(rows[0]);
}));

app.post('/credentials', asyncRoute(async (req, res) => {
  const employeeCapabilities = await getEmployeeCapabilities();
  const selectCredentialColumns = buildSelectCredentialColumns(employeeCapabilities);
  const username = String(req.body.username || '').trim().toLowerCase();
  const password = String(req.body.password || '').trim();
  const fullName = String(req.body.full_name || req.body.name || '').trim();
  const employeePosition = String(req.body.employee_position || req.body.job_title || '').trim();

  if (!username || !password) {
    return res.status(400).json({ error: 'Missing username or password' });
  }

  if (username.length > 50) {
    return res.status(400).json({ error: 'username/email exceeds max length (50)' });
  }

  if (password.length < 6 || password.length > 70) {
    return res.status(400).json({ error: 'password must be between 6 and 70 characters' });
  }

  const { columns: employeeColumns, hasColumn } = employeeCapabilities;
  const employeeIdColumn = employeeColumns.find((column) => column.Field === 'employee_id');

  if (!employeeIdColumn) {
    return res.status(500).json({ error: 'employees.employee_id column is required by API contract' });
  }

  const conflictChecks = [];
  const conflictValues = [];
  if (hasColumn('login_identifier')) {
    conflictChecks.push('LOWER(TRIM(emp.login_identifier)) = ?');
    conflictValues.push(username);
  }
  if (hasColumn('biometric_key')) {
    // Keep legacy compatibility: old schemas stored username/email in biometric_key.
    if (!hasColumn('login_identifier')) {
      conflictChecks.push('LOWER(TRIM(emp.biometric_key)) = ?');
      conflictValues.push(username);
    }
  }
  if (hasColumn('id')) {
    conflictChecks.push('LOWER(TRIM(emp.id)) = ?');
    conflictValues.push(username);
  }
  if (hasColumn('employee_name')) {
    conflictChecks.push('LOWER(TRIM(emp.employee_name)) = ?');
    conflictValues.push(username);
  }

  if (conflictChecks.length === 0) {
    return res.status(500).json({ error: 'employees table must contain id or employee_name for login lookup' });
  }

  const [existingRows] = await dbPool.query(
    `${selectCredentialColumns} WHERE ${conflictChecks.join(' OR ')} LIMIT 1`,
    conflictValues
  );

  if (existingRows.length > 0) {
    return res.status(409).json({ error: 'Account already exists' });
  }

  let explicitEmployeeId = null;
  const isEmployeeIdAutoIncrement = String(employeeIdColumn.Extra || '').toLowerCase().includes('auto_increment');
  if (!isEmployeeIdAutoIncrement) {
    const [idRows] = await dbPool.query('SELECT COALESCE(MAX(employee_id), 0) + 1 AS next_id FROM employees');
    explicitEmployeeId = Number(idRows[0].next_id);
  }

  const insertColumns = [];
  const insertValues = [];
  const generatedEmployeePublicId = `emp_${crypto.randomUUID().replace(/-/g, '').slice(0, 40)}`;
  if (hasColumn('id')) {
    insertColumns.push('id');
    insertValues.push(generatedEmployeePublicId);
  }
  if (explicitEmployeeId !== null) {
    insertColumns.push('employee_id');
    insertValues.push(explicitEmployeeId);
  }
  if (hasColumn('employee_name')) {
    insertColumns.push('employee_name');
    insertValues.push(fullName || username.substring(0, 255));
  }
  if (hasColumn('employee_position')) {
    insertColumns.push('employee_position');
    insertValues.push(employeePosition || null);
  }
  if (hasColumn('login_identifier')) {
    insertColumns.push('login_identifier');
    insertValues.push(username);
  }
  if (hasColumn('biometric_key')) {
    // For old schemas without login_identifier, biometric_key previously carried login ID.
    if (!hasColumn('login_identifier')) {
      insertColumns.push('biometric_key');
      insertValues.push(username);
    }
  }
  if (hasColumn('password')) {
    insertColumns.push('password');
    insertValues.push(password);
  }
  if (hasColumn('created_at')) {
    insertColumns.push('created_at');
    insertValues.push(Date.now());
  }

  if (insertColumns.length === 0) {
    return res.status(500).json({ error: 'No valid insert columns found in employees table' });
  }

  const placeholders = insertColumns.map(() => '?').join(', ');

  const [insertResult] = await dbPool.query(
    `INSERT INTO employees (${insertColumns.join(', ')}) VALUES (${placeholders})`,
    insertValues
  );

  let createdEmployeeId = Number(insertResult.insertId || 0);
  if (!createdEmployeeId && explicitEmployeeId !== null) {
    createdEmployeeId = explicitEmployeeId;
  }
  if (!createdEmployeeId && hasColumn('id')) {
    const [lookupRows] = await dbPool.query(
      'SELECT employee_id FROM employees WHERE LOWER(TRIM(id)) = ? ORDER BY employee_id DESC LIMIT 1',
      [generatedEmployeePublicId.toLowerCase()]
    );
    createdEmployeeId = Number(lookupRows[0] && lookupRows[0].employee_id);
  }

  if (!Number.isInteger(createdEmployeeId) || createdEmployeeId <= 0) {
    return res.status(500).json({ error: 'Unable to resolve created employee id' });
  }

  const [rows] = await dbPool.query(
    `${selectCredentialColumns} WHERE emp.employee_id = ? LIMIT 1`,
    [createdEmployeeId]
  );

  res.status(201).json(rows[0]);
}));

app.post('/credentials/:id/biometric/enroll', asyncRoute(async (req, res) => {
  const employeeCapabilities = await getEmployeeCapabilities();
  const selectCredentialColumns = buildSelectCredentialColumns(employeeCapabilities);
  const userId = toPositiveInt(req.params.id, 'id');
  const biometricKey = normalizeBiometricKey(req.body.biometric_key);
  const platform = normalizeBiometricPlatform(req.body.platform || req.body.biometric_platform);

  if (!employeeCapabilities.hasBiometricKey) {
    return res.status(500).json({
      error: 'employees.biometric_key column is required for biometric enrollment'
    });
  }

  const updates = ['biometric_key = ?'];
  const values = [biometricKey];

  if (employeeCapabilities.hasBiometricEnabled) {
    updates.push('biometric_enabled = 1');
  }
  if (employeeCapabilities.hasBiometricPlatform) {
    updates.push('biometric_platform = ?');
    values.push(platform);
  }
  if (employeeCapabilities.hasBiometricUpdatedAt) {
    updates.push('biometric_updated_at = NOW()');
  }

  values.push(userId);
  const [updateResult] = await dbPool.query(
    `UPDATE employees SET ${updates.join(', ')} WHERE employee_id = ?`,
    values
  );

  if (!updateResult.affectedRows) {
    return res.status(404).json({ error: 'User not found' });
  }

  const [rows] = await dbPool.query(
    `${selectCredentialColumns} WHERE emp.employee_id = ? LIMIT 1`,
    [userId]
  );
  return res.json(rows[0]);
}));

app.post('/credentials/:id/biometric/disable', asyncRoute(async (req, res) => {
  const employeeCapabilities = await getEmployeeCapabilities();
  const selectCredentialColumns = buildSelectCredentialColumns(employeeCapabilities);
  const userId = toPositiveInt(req.params.id, 'id');

  if (!employeeCapabilities.hasBiometricKey) {
    return res.status(500).json({
      error: 'employees.biometric_key column is required for biometric disable'
    });
  }

  const updates = ['biometric_key = NULL'];

  if (employeeCapabilities.hasBiometricEnabled) {
    updates.push('biometric_enabled = 0');
  }
  if (employeeCapabilities.hasBiometricPlatform) {
    updates.push('biometric_platform = NULL');
  }
  if (employeeCapabilities.hasBiometricUpdatedAt) {
    updates.push('biometric_updated_at = NOW()');
  }

  const [updateResult] = await dbPool.query(
    `UPDATE employees SET ${updates.join(', ')} WHERE employee_id = ?`,
    [userId]
  );

  if (!updateResult.affectedRows) {
    return res.status(404).json({ error: 'User not found' });
  }

  const [rows] = await dbPool.query(
    `${selectCredentialColumns} WHERE emp.employee_id = ? LIMIT 1`,
    [userId]
  );
  return res.json(rows[0]);
}));

app.post('/credentials/biometric/login', asyncRoute(async (req, res) => {
  const employeeCapabilities = await getEmployeeCapabilities();
  const selectCredentialColumns = buildSelectCredentialColumns(employeeCapabilities);
  const biometricKey = normalizeBiometricKey(req.body.biometric_key);
  const platform = normalizeBiometricPlatform(req.body.platform || req.body.biometric_platform);

  if (!employeeCapabilities.hasBiometricKey) {
    return res.status(500).json({
      error: 'employees.biometric_key column is required for biometric login'
    });
  }

  const whereClauses = ['LOWER(TRIM(emp.biometric_key)) = ?'];
  const whereValues = [biometricKey.toLowerCase()];

  if (employeeCapabilities.hasBiometricEnabled) {
    whereClauses.push('emp.biometric_enabled = 1');
  }
  if (employeeCapabilities.hasBiometricPlatform && platform) {
    whereClauses.push('(emp.biometric_platform IS NULL OR LOWER(TRIM(emp.biometric_platform)) = ?)');
    whereValues.push(platform);
  }

  const [rows] = await dbPool.query(
    `${selectCredentialColumns} WHERE ${whereClauses.join(' AND ')} ORDER BY emp.employee_id ASC LIMIT 1`,
    whereValues
  );

  if (rows.length === 0) {
    return res.status(404).json({ error: 'Biometric credential not found or disabled' });
  }

  return res.json(rows[0]);
}));

app.get('/timeinout', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities);
  const hasUserIdFilter = req.query.user_id !== undefined;
  const userId = hasUserIdFilter ? toPositiveInt(req.query.user_id, 'user_id') : null;

  if (hasUserIdFilter) {
    const [rows] = await dbPool.query(
      `${selectTimecardColumns} WHERE tc.employee_id = ? ORDER BY tc.time_in DESC`,
      [userId]
    );
    return res.json(rows.map(mapTimecardRow));
  }

  const [rows] = await dbPool.query(`${selectTimecardColumns} ORDER BY tc.time_in DESC`);
  res.json(rows.map(mapTimecardRow));
}));

app.get('/timeinout/active/:userId', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities);
  const userId = toPositiveInt(req.params.userId, 'userId');
  const [rows] = await dbPool.query(
    `${selectTimecardColumns} WHERE tc.employee_id = ? ORDER BY tc.time_in ASC`,
    [userId]
  );

  const mapped = rows.map(mapTimecardRow);
  const dayState = getAttendanceDayState(mapped, Date.now());
  const lastRecord = dayState.todaysRecords.length > 0
    ? dayState.todaysRecords[dayState.todaysRecords.length - 1]
    : null;

  res.json({
    user_id: userId,
    clocked_in: dayState.clockedIn,
    last_record: lastRecord,
    attendance_state: deriveAttendanceState(dayState)
  });
}));

app.post('/timeinout/log', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const employeeCapabilities = await getEmployeeCapabilities();
  const userId = toPositiveInt(req.body.user_id, 'user_id');
  const actorUserId = toOptionalPositiveInt(req.body.actor_user_id, 'actor_user_id');
  const eventTime = toPositiveTimestamp(req.body.event_time, 'event_time');
  const action = String(req.body.action || '').trim().toLowerCase();
  const locationTimeIn = req.body.location_time_in === undefined || req.body.location_time_in === null
    ? null
    : String(req.body.location_time_in).trim();
  const authMethod = normalizeAuthMethod(req.body.auth_method);
  const deviceName = normalizeDeviceName(req.body.device_name);
  const latitude = normalizeCoordinate(req.body.latitude, 'latitude');
  const longitude = normalizeCoordinate(req.body.longitude, 'longitude');

  const result = await createOrOverrideAttendanceEntry({
    timecardCapabilities,
    employeeCapabilities,
    userId,
    actorUserId,
    eventTime,
    action,
    locationTimeIn,
    authMethod,
    deviceName,
    latitude,
    longitude
  });

  return res.status(result.statusCode).json(result.body);
}));

app.post('/timeinout', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const employeeCapabilities = await getEmployeeCapabilities();
  const userId = toPositiveInt(req.body.user_id, 'user_id');
  const actorUserId = toOptionalPositiveInt(req.body.actor_user_id, 'actor_user_id');
  const timeIn = toPositiveTimestamp(req.body.time_in, 'time_in');
  const locationTimeIn = req.body.location_time_in === undefined || req.body.location_time_in === null
    ? null
    : String(req.body.location_time_in).trim();
  const authMethod = normalizeAuthMethod(req.body.auth_method);
  const deviceName = normalizeDeviceName(req.body.device_name);
  const latitude = normalizeCoordinate(req.body.latitude, 'latitude');
  const longitude = normalizeCoordinate(req.body.longitude, 'longitude');

  const result = await createOrOverrideAttendanceEntry({
    timecardCapabilities,
    employeeCapabilities,
    userId,
    actorUserId,
    eventTime: timeIn,
    action: 'in',
    locationTimeIn,
    authMethod,
    deviceName,
    latitude,
    longitude
  });

  if (result.body && result.body.error) {
    return res.status(result.statusCode).json(result.body);
  }

  res.status(result.statusCode).json(result.body.record);
}));

app.put('/timeinout/:id/clockout', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const employeeCapabilities = await getEmployeeCapabilities();
  const rawId = String(req.params.id || '').trim();
  const timeOut = toPositiveTimestamp(req.body.time_out, 'time_out');
  const locationTimeIn = req.body.location_time_in === undefined || req.body.location_time_in === null
    ? null
    : String(req.body.location_time_in).trim();
  const authMethod = normalizeAuthMethod(req.body.auth_method);
  const deviceName = normalizeDeviceName(req.body.device_name);
  const latitude = normalizeCoordinate(req.body.latitude, 'latitude');
  const longitude = normalizeCoordinate(req.body.longitude, 'longitude');

  const userId = toPositiveInt(req.body.user_id, 'user_id');
  const actorUserId = toOptionalPositiveInt(req.body.actor_user_id, 'actor_user_id');

  if (!rawId) {
    return res.status(400).json({ error: 'id is required' });
  }

  const result = await createOrOverrideAttendanceEntry({
    timecardCapabilities,
    employeeCapabilities,
    userId,
    actorUserId,
    eventTime: timeOut,
    action: 'out',
    locationTimeIn,
    authMethod,
    deviceName,
    latitude,
    longitude
  });

  if (result.body && result.body.error) {
    return res.status(result.statusCode).json(result.body);
  }

  res.status(result.statusCode).json(result.body.record);
}));

app.use((err, req, res, next) => {
  if (err && err.message && /positive integer|unix timestamp|biometric_key|platform|auth_method|device_name|latitude|longitude|actor_user_id/.test(err.message)) {
    return res.status(400).json({ error: err.message });
  }

  const mapped = dbErrorToHttp(err);
  return res.status(mapped.status).json(mapped.body);
});

async function startServer() {
  try {
    await dbPool.query('SELECT 1');
    console.log('Connected to MariaDB/MySQL database.');
    const server = app.listen(PORT, HOST, () => {
      console.log(`Mock backend API running at http://${HOST}:${PORT}`);
    });

    server.on('error', (err) => {
      if (err && err.code === 'EADDRINUSE') {
        console.error(`Port ${PORT} is already in use. Stop the existing backend process or run with a different PORT.`);
        process.exit(1);
      }

      console.error('Server startup error:', err);
      process.exit(1);
    });
  } catch (err) {
    console.error('Database connection error:', err);
    process.exit(1);
  }
}

startServer();
