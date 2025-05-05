![3e65469f-8e95-4a7f-8e5f-0a226a507272](https://github.com/user-attachments/assets/784e0e57-ae7b-4c80-80fb-9b4330752328)

## Development Status
**Current Version:** 1.3.11
**Environment:** Paper 1.21.4
**Stage:** Release

## About
SmartSort is a Minecraft plugin that uses OpenAI's GPT-4o to intelligently organize chest, barrel, and shulker box inventories. When you open a container, the plugin analyzes its contents, sends the data to an AI model, and arranges items in a logical, intuitive way.

## Key Features
- **AI-Powered Organization**: Uses OpenAI's models to determine the most intuitive item arrangement
- **Multiple Container Support**: Works with chests, barrels, and shulker boxes
- **Player Inventory Sorting**: Automatically organizes player inventories with pro-level placement
- **Automatic Operation**: Just open a container and sorting happens automatically
- **Visual & Audio Feedback**: Sound cues indicate when sorting is in progress
- **Item Protection**: Robust safeguards ensure no items are duplicated or lost
- **Performance Optimizations**: Caching, rate limiting, and thread-safe operations

## Installation

### Requirements
- Paper/Spigot server 1.21+
- Java 21+
- OpenAI API Key

### Setup Steps
1. Download the latest `smartsort-1.3.11.jar` from the releases
2. Place the JAR in your server's `plugins` folder
3. Restart your server
4. Set your OpenAI API key using one of these methods:
   - Environment variable: `OPENAI_API_KEY=your-key-here`
   - File: Create `plugins/SmartSort/apikey.txt` with just your key
   - Config: Edit `plugins/SmartSort/config.yml`
5. Restart again or use `/reload confirm`

## Usage

### Basic Usage
Simply open a chest, barrel, or shulker box. SmartSort will automatically detect the contents and organize them intelligently based on Minecraft gameplay logic.

### Player Inventory Sorting
Toggle automatic player inventory sorting with `/smartsort playerinv`. When enabled, your inventory will be intelligently organized by the AI with:
- Weapons placed in hotbar slots 1-2
- Tools placed in slots 3-5
- Building blocks in slots 6-9
- Food items on the right side of hotbar
- Armor items equipped automatically in their proper slots
- Similar items grouped together for easy access

Admins can also adjust sorting speed with `/smartsort fastmode` (toggles between 30s and 3s cooldown), and any player can force an immediate sort with `/smartsort now`.

### Commands
- `/smartsort help` - Show all available commands
- `/smartsort debug` - Toggle debug messages in your chat
- `/smartsort console` - Toggle console debug logging (admin only)
- `/smartsort test` - View available chest themes
- `/smartsort test <theme>` - Generate themed test chests (try "random" for a surprise!)
- `/smartsort playerinv` - Toggle player inventory auto-sorting
- `/smartsort fastmode` - Toggle fast/normal sorting speed (admin only)
- `/smartsort now` - Sort inventory immediately (bypasses cooldown)

### Permissions
- `smartsort.admin` - Access to plugin management commands
- `smartsort.admin.console` - Control server-side debug logging
- `smartsort.test` - Create test chests with sample inventories
- `smartsort.player` - Access to player inventory sorting feature

## Configuration
```yaml
# Version tracking
config_version: 2

openai:
  # Your OpenAI API key (can also be set via environment or apikey.txt)
  api_key: "your-api-key-here"

  # Default model to use
  model: "gpt-4o"

  # Enable dynamic model selection based on inventory size
  dynamic_model: true

  # Simplified model thresholds - just small vs large now
  model_thresholds:
    small: 13

  # Simplified models
  models:
    small: "gpt-3.5-turbo"
    large: "gpt-4o"

smart_sort:
  # Cooldown between sorts (seconds)
  delay_seconds: 3

  # Cooldown between player inventory sorts (seconds)
  player_inventory_delay_seconds: 30

  # Enable automatic player inventory sorting by default
  auto_sort_player_inventory: false

performance:
  # API rate limiting (requests per time period)
  max_requests: 3
  per_seconds: 2

  # Maximum sorted inventories to keep in memory
  cache_size: 500

  # Skip small containers for performance
  skip_small_containers: true

logging:
  # Enable detailed console logging
  console_debug: false
```

## Technical Details

### How It Works
1. **Detection**: Plugin detects when a player opens a compatible container
2. **Analysis**: Creates an inventory signature based on contained items
3. **Caching**: Checks if this exact inventory has been sorted before
4. **AI Processing**: If not cached, sends inventory data to OpenAI
5. **Sorting**: Receives and applies the AI's suggested organization
6. **Verification**: Ensures all items are preserved during sorting

### Performance Considerations
- **Caching**: Previously sorted inventories are cached to avoid redundant API calls
- **Rate Limiting**: Built-in rate limiter prevents API overuse
- **Request Queuing**: Requests are queued when rate limits are reached
- **Thread Safety**: Components designed for concurrent operation
- **Cooldowns**: Prevents repeated sorting of the same inventory

### API Key Security
The plugin offers three ways to configure your OpenAI API key:
1. **Environment Variable**: Set OPENAI_API_KEY in your server environment (most secure)
2. **Separate File**: Create plugins/SmartSort/apikey.txt containing just the key
3. **Config File**: Set in config.yml (least secure)

### AI Prompting
The plugin uses carefully crafted prompts that instruct the AI to:
- Group similar items together (blocks, tools, resources)
- Put commonly used items at the top of inventories
- Sort tools and weapons by material quality
- Arrange items in a way that's intuitive for Minecraft players
- Place player inventory items according to professional gameplay patterns

## Troubleshooting

### Common Issues
- **No Sorting Happens**: Check if your API key is set correctly
- **Slow Sorting**: Large inventories require more powerful models, which take longer
- **Rate Limiting**: If you see rate limit errors, adjust performance settings

### Debug Mode
Use `/smartsort debug` to see detailed logs in your chat, which can help identify issues.

## Changelog

### 1.3.11 (2025-05-10)
- Enhanced logging system for better debugging and troubleshooting
- Fixed thread handling to prevent inventory ticking issues
- Improved AsyncTaskManager implementation for better performance
- Added detailed inventory state tracking for safer item handling
- Optimized inventory change detection to prevent conflicts
- Fixed multiple issues with armor slot handling
- Enhanced caching system with version tracking for better reliability
- Improved AI prompt formatting for more consistent sorting results

### 1.3.10 (2025-05-07)
- Fixed player inventory sorting with enhanced armor handling
- Implemented intelligent item placement based on Minecraft pro gameplay patterns
- Fixed armor duplication and inventory item handling issues
- Improved AI prompting for more consistent and intuitive sorting
- Added sorting feedback with clear visual and audio cues
- Added fast mode toggle for player inventory sorting speed
- Added immediate sorting command for bypassing cooldown
- Enhanced code for better performance and stability

### 1.3.9 (2025-04-15)
- Unified all commands under `/smartsort` for better user experience
- Added predefined test chest themes for more consistent results
- Improved random theme selection to use a curated list
- Enhanced command help with detailed theme listings
- Fixed import warnings and optimized code

### 1.3.8 (2023-06-10)
- Implemented automated GitHub Actions-based build system
- Added Maven Release Plugin support for streamlined versioning
- Improved plugin.yml version handling with resource filtering
- Enhanced project documentation with detailed release process
- Fixed minor inventory handling bugs

### 1.3.5 (2025-04-27)
- Improved thread safety and concurrent operation
- Added multiple secure options for API key management
- Enhanced error handling and recovery mechanisms
- Implemented proper request queuing for rate limiting
- Fixed potential item loss in certain sorting scenarios

### 1.3.0 (2023-04-27)
- Fixed test chest command with improved regex handling
- Enhanced sorting quality with better AI prompting
- Added robust error handling for API responses
- Reduced test chest count from 9 to 4 to avoid rate limiting

### 1.2.6
- Added comprehensive in-game debugging tools
- Fixed chest debounce system and test chest generation

## License & Credits
- **Author**: Nicholas Tate Park
- **Status**: Private project - not for distribution
- **OpenAI Integration**: Uses OpenAI's GPT models for intelligent sorting

---

*SmartSort is a private project by Nicholas Tate Park. API costs are managed by the author.*
