module.exports = {
  apps: [
    {
      name: 'syncflow-api',
      script: 'dist/index.js',
      instances: 1,
      autorestart: true,
      watch: false,
      max_memory_restart: '500M',
      env: {
        NODE_ENV: 'production',
        PORT: 3000,
        WS_PORT: 3001,
      },
      env_development: {
        NODE_ENV: 'development',
        PORT: 3000,
        WS_PORT: 3001,
      },
      error_file: '/home/syncflow/logs/syncflow-error.log',
      out_file: '/home/syncflow/logs/syncflow-out.log',
      log_file: '/home/syncflow/logs/syncflow-combined.log',
      time: true,
    },
  ],
};
