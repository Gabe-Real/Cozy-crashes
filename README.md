# Cozy: Crashes

This repository contains a Discord bot that we make use of to help keep modded minecraft communities run smoothly.
Certain features are directly forked from QuiltMC's [Cozy Discord](https://github.com/QuiltMC/cozy-discord), that is
licensed under the Mozilla public license 2.0.

This bots features include:

* A minecraft crash report parsing system for modded and plugin-based servers
* Support for common plugin platforms (Paper, Spigot, Bukkit, Velocity, BungeeCord, Waterfall)
* Advanced URL phishing detect system
* Pluralkit config support
* Amazing developers willing to do whatever it takes to make you happy.

Most of the features currently implemented within Cozy were designed with minecraft in mind, and haven't been
factored out into reusable modules. We do plan to do this at some point, but there's a ways to go yet!


# Development Requirements

If you're here to help out, here's what you'll need. Firstly:

* A JDK, **Java 17 or later** - if you need one, try [Adoptium](https://adoptium.net/)
	* **Production builds use Java 24** for enhanced security (fewer vulnerabilities)
	* **Development with Java 17** is fully supported through Gradle toolchains
	* The build system will automatically download Java 24 if needed for compilation
* An IDE suitable for Kotlin **and Gradle** work
	* [IntelliJ IDEA](https://www.jetbrains.com/idea/): Community Edition should be plenty
	* [Eclipse](https://www.eclipse.org/ide/): Install the latest version
	  of [the Kotlin plugin](https://marketplace.eclipse.org/content/kotlin-plugin-eclipse), then go to the `Window`
	  menu, `Preferences`, `Kotlin`, `Compiler` and make sure you set up the `JDK_HOME` and JVM target version
* A database server: [Download](https://www.mongodb.com/try/download/community) and install
  | [Docker](https://hub.docker.com/_/mongo) | [Hosted](https://www.mongodb.com/atlas/database) (there's a free tier)
	* You may also want [MongoDB Compass](https://www.mongodb.com/products/compass) if you're doing database-related
	  work
* A Discord bot application, created at [the developer dashboard](https://discord.com/developers/applications). Make
  sure you turn on all the privileged intents - different modes require different intents!

# Setting Up

As a first step, fork this repository, clone your fork, and open it in your IDE, importing the Gradle project. Create
a file named `.env` in the project root (next to files like the `build.gradle.kts`), and fill it out with your bot's
settings. This file should contain `KEY=value` pairs, without a space around the `=` and without added quotes:

```dotenv
TOKEN=AAA....
DB_URL=mongodb://localhost:27017/

ENVIRONMENT=dev
# You get the idea.
```

**Required settings:**

* `TOKEN`: Your Discord bot token, which you can get from the developer dashboard linked above
* `DB_URL`: MongoDB database URL - for a local server, you might use `mongodb://localhost:27017/` for example

**Logging settings:**

* `ENVIRONMENT`: `prod` (default) for info logging on SystemErr, `dev` for debug logging on SystemOut

Once you've filled out your `.env` file, you can use the `dev` gradle task to launch the bot. If this is your first
run, you'll want to start with the `quilt` mode as this is the mode that runs the database migrations. There may
also be some library errors depending on what version of kotlin your using or what plugins you have active.
If you need any help with setting it up please feel free to join the [support server](https://discord.gabereal.co.uk)
and we will help you out. After that, feel free to set up and test whichever mode you need to work with.

# Conventions and Linting

This repository no longer makes use of detekt, so naturally we recommend you do not install it as it most likely will
not work.