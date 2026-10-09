# BootUI agent plugin

This plugin lets an agent consult a running Spring Boot or Quarkus application through BootUI. It installs:

- the `bootui` skill, which teaches the agent how to add, configure, and use BootUI safely;
- the local MCP server connection at `http://127.0.0.1:8080/bootui/api/mcp`.

The payload follows the [Agent Plugins standard](https://agent-plugins.org), so it can load in Cursor and other
compatible clients. It also carries a Claude Code manifest for installation from this repository's marketplace.

## Before using the plugin

BootUI runs inside your application; this plugin does not start it. Run the application locally, then enable its MCP
server with `bootui.mcp.enabled=ON` or from the **MCP Server** panel at `/bootui/#/mcp-server`.

BootUI serves MCP over Streamable HTTP. Agent Plugins calls that transport `streamable-http`; Claude Code and VS Code
call the same transport `http`, while Cursor's personal MCP configuration omits the type. BootUI accepts JSON-RPC
requests over `POST /bootui/api/mcp`.

## Install in GitHub Copilot CLI

Install directly from the plugin directory in this repository:

```bash
copilot plugin install jdubois/boot-ui:plugins/bootui
copilot plugin list
```

The skill can also diagnose through the BootUI CLI or plain HTTP when MCP is unavailable. Installing the plugin does
not add BootUI to an application or enable its MCP server. The bundled connection uses the default loopback endpoint;
for another port or API path, configure the correct MCP endpoint in Copilot rather than changing the application to
match the plugin. Keep credentials in your local client configuration, never in the plugin.

## Install in Cursor

After BootUI is listed in the Cursor Marketplace:

1. Open **Customize** in Cursor.
2. Find **BootUI**, select **Install**, and choose user or project scope.
3. Confirm that the `bootui` skill and MCP server appear.

Until the marketplace listing is available, test the plugin from the repository root:

```bash
mkdir -p ~/.cursor/plugins/local/bootui
cp -R plugins/bootui/. ~/.cursor/plugins/local/bootui/
```

Then restart Cursor or run **Developer: Reload Window**. Cursor deliberately skips a symlink whose target is outside
`~/.cursor/plugins/local`.

The bundled MCP server uses the default loopback endpoint. If the application uses another port or a custom
`bootui.api-path`, disable the bundled BootUI server in **Customize** and add the correct endpoint to
`~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "bootui": {
      "url": "http://127.0.0.1:8081/bootui/api/mcp"
    }
  }
}
```

For an application reached from another host or container, add the bearer token yourself; the plugin never ships or
stores credentials:

```json
{
  "mcpServers": {
    "bootui": {
      "url": "http://localhost:8080/bootui/api/mcp",
      "headers": {
        "Authorization": "Bearer <bootui.authentication.token>"
      }
    }
  }
}
```

## Install in Claude Code

Add this repository's marketplace and install the plugin:

```text
/plugin marketplace add jdubois/boot-ui
/plugin install bootui@bootui
```

Claude Code reads the client-specific manifest in `.claude-plugin/plugin.json`. Set `BOOTUI_MCP_URL` before starting
Claude Code when the application does not use the default endpoint. For non-loopback access, register the server
manually so its `Authorization` header stays in your local configuration rather than in the plugin.

For complete setup, safety, and tool documentation, see the
[BootUI AI agents guide](https://www.julien-dubois.com/boot-ui/ai-agents).
