# Claude3270
Collaboration effort with AI to produce an Open Source TN3270 emulator in Java

## [Please read the src/Claude3270_QuickStart.md file for Quick Start information]

When my absolute favorite TN3270 emulator stopped working for a few of my target
hosts, I started looking for a suitable replacement.  As I use MacOS, the replacement 
options were limited, but reasonably priced.  

I have background knowledge of 3270 data streams and related file transfer protocols
in general, so I thought to have a conversation with Claude.ai to determine if it 
would be possible to quickly compose a usable TN3270 emulator with the basic functions
that I require.  The conversations were few and scattered over several weeks as I was
bound to the usage restrictions of Claude.ai.  The result of these conversations is
Claude3270, a bare-bones TN3270 emulator written entirely in Java.

Although other Open Source TN3270 emulators exist, none truly appealed to me.  During
several conversations with Claude.ai, I shared large portions of some Assembler code that
I wrote almost 45 years ago.  Claude.ai was able to translate the Assembler functions
to Java, and interpret much of the Assembler logic in order to create a Java implementation.
These Assembler snippets were mostly concerned with file transfer protocols that are not
well documented.  I applaud Claude.ai's ability to decipher my Assembler and make sense
of it.  Claude.ai was gracious while perusing my code and offered several kind remarks on 
how essential they were to the project.

ChatGPT was also part of this effort, but was used mostly for reading tcpdumps and
troubleshooting various aspects of the code.  Claude.ai, however, really did the heavy
lifting, with Gemini adding the UI polish.

Although Claude3270 is a usable TN3270 emulator, the ultimate goal of this project
morphed into content suitable for a VM Workshop presentation.  

I hope that you find this project amusing and useful.

Arty Ecock
November, 2025
