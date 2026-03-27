// In-memory ring buffer log capture for admin log viewer
// + Structured JSON logger for server-side observability

interface LogEntry {
  timestamp: string;
  level: 'info' | 'warn' | 'error';
  message: string;
  source?: string;
}

const MAX_ENTRIES = 1000;
const buffer: LogEntry[] = [];

function pushEntry(level: LogEntry['level'], args: any[]) {
  const message = args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ');
  buffer.push({
    timestamp: new Date().toISOString(),
    level,
    message,
  });
  if (buffer.length > MAX_ENTRIES) {
    buffer.shift();
  }
}

export function initLogger() {
  const origLog = console.log.bind(console);
  const origError = console.error.bind(console);
  const origWarn = console.warn.bind(console);

  console.log = (...args: any[]) => {
    pushEntry('info', args);
    origLog(...args);
  };
  console.error = (...args: any[]) => {
    pushEntry('error', args);
    origError(...args);
  };
  console.warn = (...args: any[]) => {
    pushEntry('warn', args);
    origWarn(...args);
  };
}

export function getLogs(
  limit = 100,
  level?: string,
  search?: string
): { logs: LogEntry[]; total: number } {
  let filtered = buffer;

  if (level && level !== 'all') {
    filtered = filtered.filter(e => e.level === level);
  }
  if (search) {
    const lower = search.toLowerCase();
    filtered = filtered.filter(e => e.message.toLowerCase().includes(lower));
  }

  const total = filtered.length;
  // Return most recent first
  const logs = filtered.slice(-limit).reverse();
  return { logs, total };
}

export function clearLogs() {
  buffer.length = 0;
}

// ---------------------------------------------------------------------------
// Structured logger
//
// Outputs JSON lines with { level, message, timestamp, ...context }.
// log.debug() is suppressed in production to reduce noise.
// ---------------------------------------------------------------------------

type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface StructuredEntry {
  level: LogLevel;
  message: string;
  timestamp: string;
  [key: string]: unknown;
}

function emit(level: LogLevel, message: string, context?: Record<string, unknown>): void {
  const entry: StructuredEntry = {
    level,
    message,
    timestamp: new Date().toISOString(),
    ...context,
  };

  const line = JSON.stringify(entry);

  switch (level) {
    case 'error':
      console.error(line);
      break;
    case 'warn':
      console.warn(line);
      break;
    default:
      console.log(line);
      break;
  }
}

const isProduction = process.env.NODE_ENV === 'production';

export const log = {
  debug(message: string, context?: Record<string, unknown>): void {
    if (!isProduction) {
      emit('debug', message, context);
    }
  },
  info(message: string, context?: Record<string, unknown>): void {
    emit('info', message, context);
  },
  warn(message: string, context?: Record<string, unknown>): void {
    emit('warn', message, context);
  },
  error(message: string, context?: Record<string, unknown>): void {
    emit('error', message, context);
  },
};
