# DoubleDump (dx2)

DoubleDump (CLI command: `dx2`) is the Java translation of the original `mediadup-full.sh` media deduper. The CLI mirrors the bash script commands:

```
./gradlew run --args="find-duplicates /path [--cache-db PATH] [--jobs N] [--action print|hardlink|symlink|move|none] [--trash-dir PATH]"
./gradlew run --args="compare file1 file2"
./gradlew run --args="compare-pixels file1 file2"
./gradlew run --args="hash file"
```

The Java implementation still relies on `exiftool`, `dcraw`, `ffmpeg`, ImageMagick (`magick`/`convert`/`identify`), `compare`, and SQLite being available on the host, just like the original bash script. Install the dependencies before running the tool.
