// Mock backend API for Timekeeper app (MariaDB/MySQL)
const crypto = require('crypto');
const express = require('express');
const mysql = require('mysql2/promise');

const app = express();
const PORT = Number(process.env.PORT || 3000);

app.use(express.json());

const dbPool = mysql.createPool({
  host: process.env.DB_HOST || 'localhost',
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || 'Root123456789',
  database: process.env.DB_NAME || 'local_timeclock',
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
});

const SELECT_CREDENTIAL_COLUMNS = `
  SELECT
    emp.employee_id AS id,
    COALESCE(
      CASE
        WHEN emp.biometric_key IS NOT NULL AND TRIM(emp.biometric_key) LIKE '%@%' THEN LOWER(TRIM(emp.biometric_key))
        ELSE NULL
      END,
      CASE
        WHEN emp.id IS NOT NULL AND emp.id LIKE '%@%' THEN LOWER(TRIM(emp.id))
        WHEN emp.employee_name IS NOT NULL AND emp.employee_name LIKE '%@%' THEN LOWER(TRIM(emp.employee_name))
        ELSE NULL
      END,
      LOWER(TRIM(emp.employee_name))
    ) AS username,
    emp.password AS password,
    CASE
      WHEN emp.employee_name IS NULL OR TRIM(emp.employee_name) = '' THEN 'Unknown User'
      WHEN emp.employee_name LIKE '%@%' THEN SUBSTRING_INDEX(TRIM(emp.employee_name), '@', 1)
      ELSE TRIM(emp.employee_name)
    END AS full_name,
    NULLIF(TRIM(emp.employee_position), '') AS employee_position,
    CASE
      WHEN emp.biometric_key IS NOT NULL AND TRIM(emp.biometric_key) LIKE '%@%' THEN LOWER(TRIM(emp.biometric_key))
      WHEN emp.id IS NOT NULL AND emp.id LIKE '%@%' THEN LOWER(TRIM(emp.id))
      WHEN emp.employee_name IS NOT NULL AND emp.employee_name LIKE '%@%' THEN LOWER(TRIM(emp.employee_name))
      ELSE NULL
    END AS email
  FROM employees AS emp
`;

function buildSelectTimecardColumns(hasRecordNo) {
  return `
    SELECT
      ${hasRecordNo ? 'CAST(tc.record_no AS CHAR)' : 'tc.id'} AS id,
      tc.employee_id AS user_id,
      tc.time_in AS time_in,
      tc.time_out AS time_out,
      tc.location_time_in AS location_time_in,
      tc.location_time_out AS location_time_out,
      tc.created_date AS created_date,
      tc.modified_date AS modified_date
    FROM timecard AS tc
  `;
}

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

function parseTimestampMs(value) {
  if (value === null || value === undefined) {
    return null;
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
  const timeInMs = parseTimestampMs(row.time_in);
  const timeOutMs = parseTimestampMs(row.time_out);
  const createdDateMs = parseTimestampMs(row.created_date);
  const modifiedDateMs = parseTimestampMs(row.modified_date);

  return {
    id: String(row.id),
    user_id: Number(row.user_id),
    time_in: formatTimestampReadable(timeInMs),
    time_out: formatTimestampReadable(timeOutMs),
    time_in_ms: timeInMs,
    time_out_ms: timeOutMs,
    created_date: formatTimestampReadable(createdDateMs),
    modified_date: formatTimestampReadable(modifiedDateMs),
    created_date_ms: createdDateMs,
    modified_date_ms: modifiedDateMs,
    location_time_in: row.location_time_in || null,
    location_time_out: row.location_time_out || null
  };
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
    hasRecordNo: fields.has('record_no')
  };
}

app.get('/credentials', asyncRoute(async (req, res) => {
  const [rows] = await dbPool.query(`${SELECT_CREDENTIAL_COLUMNS} ORDER BY emp.employee_id ASC`);
  res.json(rows);
}));

app.get('/credentials/:id', asyncRoute(async (req, res) => {
  const credentialId = toPositiveInt(req.params.id, 'id');
  const [rows] = await dbPool.query(
    `${SELECT_CREDENTIAL_COLUMNS} WHERE emp.employee_id = ? LIMIT 1`,
    [credentialId]
  );

  if (rows.length === 0) {
    return res.status(404).json({ error: 'User not found' });
  }

  res.json(rows[0]);
}));

app.post('/credentials', asyncRoute(async (req, res) => {
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

  const [employeeColumns] = await dbPool.query('SHOW COLUMNS FROM employees');
  const hasColumn = (columnName) => employeeColumns.some((column) => column.Field === columnName);
  const employeeIdColumn = employeeColumns.find((column) => column.Field === 'employee_id');

  if (!employeeIdColumn) {
    return res.status(500).json({ error: 'employees.employee_id column is required by API contract' });
  }

  const conflictChecks = [];
  const conflictValues = [];
  if (hasColumn('biometric_key')) {
    conflictChecks.push('LOWER(TRIM(emp.biometric_key)) = ?');
    conflictValues.push(username);
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
    `${SELECT_CREDENTIAL_COLUMNS} WHERE ${conflictChecks.join(' OR ')} LIMIT 1`,
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
  if (hasColumn('biometric_key')) {
    insertColumns.push('biometric_key');
    insertValues.push(username);
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
      [username]
    );
    createdEmployeeId = Number(lookupRows[0] && lookupRows[0].employee_id);
  }

  if (!Number.isInteger(createdEmployeeId) || createdEmployeeId <= 0) {
    return res.status(500).json({ error: 'Unable to resolve created employee id' });
  }

  const [rows] = await dbPool.query(
    `${SELECT_CREDENTIAL_COLUMNS} WHERE emp.employee_id = ? LIMIT 1`,
    [createdEmployeeId]
  );

  res.status(201).json(rows[0]);
}));

app.get('/timeinout', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities.hasRecordNo);
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
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities.hasRecordNo);
  const userId = toPositiveInt(req.params.userId, 'userId');
  const [rows] = await dbPool.query(
    `${selectTimecardColumns} WHERE tc.employee_id = ? AND tc.time_out IS NULL ORDER BY tc.time_in DESC LIMIT 1`,
    [userId]
  );

  if (rows.length === 0) {
    return res.status(200).json(null);
  }

  res.json(mapTimecardRow(rows[0]));
}));

app.post('/timeinout', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities.hasRecordNo);
  const userId = toPositiveInt(req.body.user_id, 'user_id');
  const timeIn = toPositiveTimestamp(req.body.time_in, 'time_in');
  const locationTimeIn = req.body.location_time_in === undefined || req.body.location_time_in === null
    ? null
    : String(req.body.location_time_in).trim();

  const [activeRows] = await dbPool.query(
    `${selectTimecardColumns} WHERE tc.employee_id = ? AND tc.time_out IS NULL LIMIT 1`,
    [userId]
  );

  if (activeRows.length > 0) {
    return res.status(409).json({ error: 'User already has an active clock-in record' });
  }

  const timecardUuid = crypto.randomUUID().replace(/-/g, '');
  const auditActor = `employee:${userId}`;
  const createdDate = Date.now();

  const [insertResult] = await dbPool.query(
    `
      INSERT INTO timecard (
        id,
        employee_id,
        time_in,
        location_time_in,
        created_by,
        created_date,
        time_in_type
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `,
    [timecardUuid, userId, timeIn, locationTimeIn, auditActor, createdDate, 1]
  );

  let createdWhereClause = 'tc.id = ?';
  let createdWhereValue = timecardUuid;
  if (timecardCapabilities.hasRecordNo) {
    createdWhereClause = 'tc.record_no = ?';
    createdWhereValue = Number(insertResult.insertId);
  }

  const [rows] = await dbPool.query(
    `${selectTimecardColumns} WHERE ${createdWhereClause} LIMIT 1`,
    [createdWhereValue]
  );

  res.status(201).json(mapTimecardRow(rows[0]));
}));

app.put('/timeinout/:id/clockout', asyncRoute(async (req, res) => {
  const timecardCapabilities = await getTimecardCapabilities();
  const selectTimecardColumns = buildSelectTimecardColumns(timecardCapabilities.hasRecordNo);
  const rawId = String(req.params.id || '').trim();
  if (!rawId) {
    return res.status(400).json({ error: 'id is required' });
  }

  const whereClause = timecardCapabilities.hasRecordNo ? 'tc.record_no = ?' : 'tc.id = ?';
  const whereValue = timecardCapabilities.hasRecordNo ? toPositiveInt(rawId, 'id') : rawId;
  const timeOut = toPositiveTimestamp(req.body.time_out, 'time_out');
  const locationTimeOut = req.body.location_time_out === undefined || req.body.location_time_out === null
    ? null
    : String(req.body.location_time_out).trim();

  const connection = await dbPool.getConnection();
  try {
    await connection.beginTransaction();

    const [rows] = await connection.query(
      `${selectTimecardColumns} WHERE ${whereClause} LIMIT 1 FOR UPDATE`,
      [whereValue]
    );

    if (rows.length === 0) {
      await connection.rollback();
      return res.status(404).json({ error: 'Time record not found' });
    }

    const existing = mapTimecardRow(rows[0]);
    if (existing.time_out !== null) {
      await connection.rollback();
      return res.status(409).json({ error: 'Record already clocked out' });
    }

    if (!existing.time_in_ms || timeOut <= existing.time_in_ms) {
      await connection.rollback();
      return res.status(400).json({ error: 'time_out must be greater than time_in' });
    }

    await connection.query(
      `
        UPDATE timecard AS tc
        SET
          tc.time_out = ?,
          tc.location_time_out = ?,
          tc.modified_by = ?,
          tc.modified_date = ?,
          tc.time_out_type = ?
        WHERE ${whereClause}
      `,
      [timeOut, locationTimeOut, `employee:${existing.user_id}`, Date.now(), 1, whereValue]
    );

    await connection.commit();

    const [updatedRows] = await connection.query(
      `${selectTimecardColumns} WHERE ${whereClause} LIMIT 1`,
      [whereValue]
    );

    res.json(mapTimecardRow(updatedRows[0]));
  } finally {
    connection.release();
  }
}));

app.use((err, req, res, next) => {
  if (err && err.message && /positive integer|unix timestamp/.test(err.message)) {
    return res.status(400).json({ error: err.message });
  }

  const mapped = dbErrorToHttp(err);
  return res.status(mapped.status).json(mapped.body);
});

async function startServer() {
  try {
    await dbPool.query('SELECT 1');
    console.log('Connected to MariaDB/MySQL database.');
    app.listen(PORT, () => {
      console.log(`Mock backend API running at http://localhost:${PORT}`);
    });
  } catch (err) {
    console.error('Database connection error:', err);
    process.exit(1);
  }
}

startServer();
