'use strict';

require('dotenv').config({ path: require('node:path').join(__dirname, '..', '..', '..', '.env.local') });
const postgres = require('postgres');

async function main() {
  if (!process.env.SUPABASE_DB_URL) throw new Error('SUPABASE_DB_URL is not configured.');
  const sql = postgres(process.env.SUPABASE_DB_URL, {
    ssl: 'require',
    prepare: false,
    max: 1,
  });
  try {
    const tables = await sql`
      select table_name
      from information_schema.tables
      where table_schema = 'ctrlb_private'
        and table_name in ('billing_accounts', 'billing_customers', 'billing_events')
      order by table_name
    `;
    const actual = tables.map(({ table_name: tableName }) => tableName);
    const expected = ['billing_accounts', 'billing_customers', 'billing_events'];
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      throw new Error(`Billing schema is incomplete: found ${actual.join(', ') || 'no tables'}.`);
    }
    console.log(`Verified live Supabase billing tables: ${actual.join(', ')}`);
  } finally {
    await sql.end();
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
