// In-memory ring buffer log capture for admin log viewer

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
