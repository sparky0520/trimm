const { createClient } = require('@supabase/supabase-js');
const supabase = createClient('https://gahrfsmpnakrjmvhikzf.supabase.co', 'sb_secret_F0ZJGyWXmG41K4AbcFlHFg_VW4rLkzK');
async function run() {
  const { data, error } = await supabase.from('chats').select('project_data').order('created_at', { ascending: false }).limit(1);
  if (error) console.error(error);
  else console.log(JSON.stringify(data, null, 2));
}
run();
