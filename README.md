# NoteBlockWorldMC
Note: This is a plugin that is a primary product of Vibe Coding/AI, mixed with my dream of making a decent plugin that'd be used for fun.

In short, this plugin allows people with permission (defaulted to OP, since in my opinion, only OPs should have the permission to use this plugin), to play .nbs (Note Block Studio) songs of their choice.
This plugin, by default, fetches every song from https://noteblock.world, which is a site ran by the developers of OpenNBS, for Note Block Studio composer enthusiasts to share their works so others could listen to them.
I tried it to make work as optimized as possible, though again, it was made by AI so I'm unsure if it's optimized for crowded servers... I'd appreciate input ;w;

# Features

1) This plugin fetches every upload within Noteblock.world, as such, you can search, filter and sort songs within a neat little GUI or via command. I'm keeping things thoroughly short here, because I'll add videos that demonstrate these features! Here's one for the GUI design.


https://github.com/user-attachments/assets/acbb6d7a-f83f-4ef8-8627-4a361833a5a2

And here's a video with the plugin in action!



https://github.com/user-attachments/assets/f3afdc30-3df5-4bc1-bb8d-de8ad91b6028




2) This plugin has an automatic resource pack building system for extended note block sounds as well as custom instruments. It requires the server IP to be set within the config.yml upon first run-- then reloading the plugin would suffice. It actually pulls custom instruments if there are any packed within the uploaded song, and adds them into a resource pack before pushing it to players.



https://github.com/user-attachments/assets/1b87b5d9-183a-403c-99d2-56422b283d76



3) It's got a few features such as putting your own .nbs songs that you've converted within NBS program locally into the plugin's "Imports" file, or downloading .nbs songs from third party sites, either works.

I need to make a better README for this, but for now, this should suffice. Trust me, there's more features than what I've mentioned. I'm also open to suggestions or issues that you may encounter.. ;w;

# Commands

"/nbw analyze [<songName>]"
This command allows the player to see details regarding the song. If an argument is provided, it checks the song provided in the argument. If there's no argument provided yet a song is currently playing; it shows the information of currently playing song.

This command lists the amount of notes, it's tempo/speed, and "Peak", which signs the polyphony peak of the song. As to what Polyphony/Density is, it's a long story.. Just know that Minecraft can only play 247 sounds at most. (Whether it "fits comfortably" or not depends on this polyphony limit. This plugin does everything in it's power to keep the song optimally playable for players, but this is a limit that cannot be extended without installing mods... t-t)

"/nbw categories"
This command simply lists all the available categories that have the songs listed in, which you can click over for results of your liking.

"/nbw config [<configSetting>] [<value>]"
This command allows some of the config settings that are not hard-coded to be edited within the server.

If you use the command without arguments, it lists all the adjustable settings. If you use the command with an argument but leaving the value blank, you can read the description that's attached to it.

And if you use the command with both arguments, it just does its job. :3

"/nbw download [<songID>]"
This is the non-GUI way to paste and download noteblock.world songs, both ID's and noteblock.world links work for this command.

"/nbw help"
This command just lists all the commands, what they do, and at last, flags that are used for "/nbw search" command later on.

"/nbw import" & "/nbw importurl [<directURL>] [<fileName>]"
This command was explained in the previous sector, so I'm skipping this- it allows you to import songs locally or via direct links that lead to .nbs files.

"/nbw packinfo"
This is a debug command for the resource pack with information regarding its size, the number of instruments (vanilla instruments are 120 in total), which port it is ported through, etc.

"/nbw play [<player/@a/songName/URL>] [<songName/URL>]"
This command is the non-GUI way to download and play songs. If there is no player depicted but only songName/URL, it downloads&plays the song to the command executor. If there is a target (player/@a) depicted, following with songName or URL argument, it downloads and plays the song for the target.

"/nbw playing"
This command is to check whether a song is playing for someone, or a song is broadcasted to all players.

"/nbw reload"
Uhh.. plainly obvious, it reloads both config.yml and messages.yml X'D

"/nbw remove [<songName>]"
The non-GUI way to remove a song. Tab-completion allows you to remove the file by name. Simple enough, isn't it?

"/nbw search [<flag>] [<keyword>]"
This is the main search function. Without providing arguments, it opens up a dialog where you can search with or without flags since they are listed above the search box as a tip. You can also use those flags and arguments without resorting to the dialog, and the results will also be shown in the GUI.

"/nbw stop [<player/@a>]"
Also self-explanatory...

"/nbw volume [<0-100>]"
This command allows players to set the volume of playing songs for themselves. The volumes are persistently stored in a file called "volumes.yml" for everyone that has their volume set other than 100.


So uhh.. Have fun?.. ;w;
