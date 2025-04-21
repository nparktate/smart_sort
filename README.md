# SmartSort - AI-Powered Inventory Organization for Minecraft

## Development Status
**Current Version:** 1.2.6-SNAPSHOT+013
**Environment:** Personal server (Paper 1.21.4)
**Stage:** Implementation phase

## About
SmartSort is my custom Minecraft plugin that uses OpenAI's GPT-4o to intelligently organize chest, barrel, and shulker box inventories. It's currently being implemented on my private server.

## How It Works
When you open a container, SmartSort automatically:
1. Analyzes your chest/barrel/shulker contents
2. Sends item data to the OpenAI model
3. Gets a suggested organization scheme
4. Rearranges your items logically
5. Provides sound feedback during sorting

## Features I've Built
- **Automatic Sorting**: Just open a container and it happens
- **Compatible Containers**: Works with chests, barrels, and shulker boxes
- **Visual Feedback**: Sound cues tell you when sorting is happening
- **Item Protection**: Won't duplicate or lose items during sorting
- **Cooldown System**: Avoids redundant sorting for performance

## Admin & Dev Tools
- **Debug System**: Toggle with `/smartsort debug`
- **Player Subscription**: Get debug in-game with `/smartsort subscribe`
- **Output Control**: Choose between console/player with `/smartsort output`
- **Test Generation**: Create sample chests with `/testsortchests`

## Recent Changes
- **1.2.6-SNAPSHOT+013**: Implementation phase and version control improvements
- **1.2.6-SNAPSHOT+012**: Improved debug system, removed sensitive tokens
- **1.2.6**: Added comprehensive in-game debugging tools
- **1.2.5**: Fixed chest debounce system and test chest generation
- **1.2.4**: Added test chest command for faster testing

## Setup Notes
1. Drop the JAR into the server's plugins folder
2. Restart the server
3. Edit `config.yml` to add my API key
4. Key permissions are:
   - `smartsort.admin` - For plugin management
   - `smartsort.test` - For test chest creation

## Configuration
```yaml
openai:
  api_key: "my-api-key-here"
  model: "gpt-4o"

smart_sort:
  delay_seconds: 3

debug:
  enabled: false
  to_console: true
```

## Commands (As Server Op)
- `/smartsort debug` - Toggle debug mode
- `/smartsort subscribe` - Get debug messages in chat
- `/smartsort output <console|player>` - Where debug goes
- `/smartsort help` - Show command list
- `/testsortchests` - Generate 9 themed test chests

## Dev Notes
- Plugin works best with Paper/Spigot 1.21+
- Uses Java 21+ features
- Current test server: Paper 1.21.4-R0.1-SNAPSHOT
- Built with Maven (use `mvn package` to build)
- Debug mode helps identify sorting issues

---

*Private project by Nicholas Tate Park (Nick Park) - not for distribution.*
