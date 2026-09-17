import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import fetch from "node-fetch";

// The Fontsource API is an excellent open-source proxy for Google Fonts
const FONTSOURCE_API = "https://api.fontsource.org/v1";

const server = new McpServer({
  name: "google-fonts",
  version: "1.0.0"
});

server.tool(
  "search_google_fonts",
  "Search for a Google Font and get its direct .ttf URL.",
  {
    query: z.string().describe("The name of the font (e.g. 'Roboto', 'Open Sans')")
  },
  async ({ query }) => {
    try {
      const response = await fetch(`${FONTSOURCE_API}/fonts`);
      if (!response.ok) {
        return { content: [{ type: "text", text: "Failed to fetch fonts list" }] };
      }
      
      const fonts = await response.json();
      const match = fonts.find(f => f.family.toLowerCase().includes(query.toLowerCase()));
      
      if (!match) {
        return { content: [{ type: "text", text: `No font found matching "${query}"` }] };
      }

      // Fetch specific font details to get the TTF url
      const detailResponse = await fetch(`${FONTSOURCE_API}/fonts/${match.id}`);
      const details = await detailResponse.json();

      // Look for a standard TTF url for normal weight
      let ttfUrl = '';
      if (details.variants && details.variants['400'] && details.variants['400'].normal) {
        // Fontsource provides TTF in the 'ttf' subset or local url
        const variant = details.variants['400'].normal;
        if (variant.latin && variant.latin.url && variant.latin.url.ttf) {
          ttfUrl = variant.latin.url.ttf;
        } else {
          // fallback to woff if ttf missing (though FFmpeg prefers ttf)
          ttfUrl = variant.latin?.url?.woff2 || variant.latin?.url?.woff || "URL not found";
        }
      }

      return {
        content: [{
          type: "text",
          text: JSON.stringify({
            family: match.family,
            id: match.id,
            category: match.category,
            url: ttfUrl
          }, null, 2)
        }]
      };
    } catch (e) {
      return { content: [{ type: "text", text: `Error: ${e.message}` }] };
    }
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch(console.error);
