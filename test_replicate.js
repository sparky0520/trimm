async function test() {
  require('dotenv').config({ path: 'cloud/services/backend/.env' });
  const replicateApiToken = process.env.REPLICATE_API_TOKEN;
  if (!replicateApiToken) {
    console.log("No token, skipping test.");
    return;
  }
  const response = await fetch('https://api.replicate.com/v1/models/meta/musicgen/predictions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${replicateApiToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      input: { prompt: "chill lo-fi beats", duration: 2 }
    })
  });
  console.log(response.status);
  console.log(await response.text());
}
test();
