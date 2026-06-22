DeadDrop Desktop for Windows
============================

Recommended install
-------------------

Use the native installer when available:

DeadDrop-Desktop-<version>-Setup.exe

It installs DeadDrop Desktop for the current user under:

%LOCALAPPDATA%\Programs\DeadDrop Desktop

The installer includes the Java runtime needed by the app. A separate Java install is not required for the native package.

Portable app image
------------------

If you received the native app-image zip instead of the installer, unzip it and run:

DeadDrop Desktop.exe

Java launcher package
---------------------

If you are using the ZIP package instead of the native installer, install Java 17 or newer and run:

install-deaddrop-windows.cmd

Uninstall
---------

Use Windows Settings > Apps, or the Start menu uninstall entry created by the installer.

Uninstall removes application files and shortcuts only. It does not remove your encrypted local vault:

%USERPROFILE%\.config\deaddrop-desktop\vault.ddv

Audio notes
-----------

Use Device test first. It plays a short tone, records a short in-memory diagnostic sample from the selected listen source, and reports peak/RMS/clipping. If Java exposes compatible audio devices, choose the preferred microphone/line-in and output devices in the dropdowns.

For browser/app audio already playing through Windows speakers/headphones, set Listen source to "System output / what this computer is playing". The package includes deaddrop-wasapi-loopback.ps1, a small WASAPI loopback helper that streams the default render endpoint to DeadDrop in memory. Exclusive-mode audio or unusual drivers may still block loopback capture.

Security status
---------------

DeadDrop is early unaudited software. It has no accounts or network service, and normal operation does not store audio files. The desktop vault is local and passphrase-protected.
