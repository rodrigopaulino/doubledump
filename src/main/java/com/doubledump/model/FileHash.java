package com.doubledump.model;

import java.nio.file.Path;

public record FileHash(String hash, Path path) {
}
