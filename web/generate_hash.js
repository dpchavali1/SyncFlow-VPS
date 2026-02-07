async function hash(password) {
  const encoder = new TextEncoder();
  const data = encoder.encode(password);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

// Get password from command line argument
const password = process.argv[2];
if (!password) {
  console.log('Usage: node generate_hash.js <password>');
  process.exit(1);
}

hash(password).then(hash => {
  console.log(`Password: ${password}`);
  console.log(`Hash: ${hash}`);
});
