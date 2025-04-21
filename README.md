# SmartSort Plugin - AI-Assisted Inventory Organizer

## Overview
SmartSort is a Minecraft server plugin that uses OpenAI's GPT-4o model to intelligently organize chest, barrel, and shulker box inventories. When a player opens a compatible inventory, SmartSort automatically categorizes and arranges items in a logical manner, making storage management effortless.

## Key Features
- **AI-Powered Organization**: Uses advanced AI to understand item relationships and sort them intelligently
- **Automatic Sorting**: Activates when players open chests, barrels, or shulker boxes
- **Visual Feedback**: Provides sound effects to indicate sorting is in progress
- **Cooldown System**: Prevents excessive sorting operations on the same inventory
- **Compatible Containers**: Works with chests, barrels, and shulker boxes
- **Developer Tools**: Comprehensive debug system for troubleshooting

## Installation
1. Download the SmartSort-1.2.6.jar file
2. Place it in your server's `plugins` folder
3. Start or restart your server
4. Edit the `plugins/SmartSort/config.yml` file to add your OpenAI API key

## Configuration
The plugin uses a simple configuration file located at `plugins/SmartSort/config.yml`:

```yaml
openai:
  api_key: "your-api-key-here"  # Required: Your OpenAI API key
  model: "gpt-4o"               # Optional: The model to use (default: gpt-4o)

smart_sort:
  delay_seconds: 3              # Optional: Cooldown between sorts (default: 3)

debug:
  enabled: false                # Optional: Enable debug logging
  to_console: true              # Optional: Send debug to console (true) or players (false)
```

## Commands
- `/smartsort debug` - Toggle debug mode on/off for detailed logging
- `/smartsort subscribe` - Subscribe to receive debug messages in your chat
- `/smartsort output <console|player>` - Set where debug messages should be sent
- `/smartsort help` - Show all available commands
- `/testsortchests` - Creates a 3x3 grid of chests filled with themed random items (useful for testing)

## Permissions
- `smartsort.admin` - Access to all SmartSort admin commands
- `smartsort.test` - Permission to generate test chests

## Debug System
SmartSort includes a comprehensive debug system for developers and server admins:

1. Enable debug mode with `/smartsort debug`
2. Choose where debug messages appear with `/smartsort output console` or `/smartsort output player`
3. If using player output, subscribe with `/smartsort subscribe`

Debug logs include:
- API requests and responses
- Inventory processing details
- Timing information
- Error reports

## How It Works
1. When a player opens a supported container, the plugin analyzes its contents
2. It sends a description of the inventory to OpenAI's API
3. The AI responds with a sorted arrangement of items
4. SmartSort rearranges the items according to the AI's recommendation
5. Sound effects provide feedback during and after the sorting process

## Requirements
- Minecraft 1.21+ (Paper/Spigot server)
- Java 21+
- Valid OpenAI API key with access to GPT-4o

## Troubleshooting
- If sorting doesn't trigger, check console logs for any API errors
- Enable debug mode with `/smartsort debug` for detailed logs
- Ensure your OpenAI API key is valid and has sufficient quota
- The plugin won't sort containers that were recently sorted (cooldown period)

## Version History
- **1.2.6**: Added comprehensive debug system with in-game commands
- **1.2.5**: Improved chest debounce system and test chest slot filling
- **1.2.4**: Added test chest generation command
- **1.2.1**: Fixed item duplication issues
- **1.2.0**: Added support for barrels and shulker boxes
- **1.1.0**: Added visual and audio feedback
- **1.0.0**: Initial release

## Support
For issues, feature requests, or questions, please create an issue on our GitHub repository.

---

*Note: This plugin requires an OpenAI API key with access to GPT-4o. API usage will incur charges according to OpenAI's pricing structure.*
