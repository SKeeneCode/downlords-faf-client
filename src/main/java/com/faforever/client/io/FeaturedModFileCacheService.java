package com.faforever.client.io;

import com.faforever.client.api.dto.FeaturedModFile;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.task.ResourceLocks;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;


@Service
@Slf4j
public class FeaturedModFileCacheService implements InitializingBean {
  private final Path cacheDirectory;
  private final int cacheLifeTimeInDays;

  public FeaturedModFileCacheService(PreferencesService preferencesService) {
    this.cacheDirectory = preferencesService.getCacheDirectory();
    this.cacheLifeTimeInDays = preferencesService.getPreferences().getCacheLifeTimeInDays();
  }

  public boolean isCached(FeaturedModFile featuredModFile) {
    return Files.exists(getCachedFilePath(featuredModFile));
  }

  private String readHashFromFile(Path filePath) {
    // see buildCachedFileName
    return filePath.getFileName().toString().split("\\.")[3];
  }

  private String buildCachedFileName(FeaturedModFile featuredModFile) {
    return String.format(
        "%s.%s.%s.%s",
        featuredModFile.getId(),
        featuredModFile.getVersion(),
        featuredModFile.getMd5(),
        featuredModFile.getName()
    );
  }

  public Path getCachedFilePath(FeaturedModFile featuredModFile) {
    return cacheDirectory
        .resolve(featuredModFile.getGroup())
        .resolve(buildCachedFileName(featuredModFile));
  }

  public void copyFeaturedModFileFromCache(FeaturedModFile featuredModFile, Path targetPath) throws IOException {
    Files.createDirectories(targetPath.getParent());
    ResourceLocks.acquireDiskLock();

    try {
      Files.copy(getCachedFilePath(featuredModFile), targetPath, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      ResourceLocks.freeDiskLock();
    }
  }

  @SneakyThrows
  public void copyFeaturedModFileFromCache(Path cacheFilePath, Path targetPath) {
    Files.createDirectories(targetPath.getParent());
    ResourceLocks.acquireDiskLock();

    try {
      Files.copy(cacheFilePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      ResourceLocks.freeDiskLock();
    }
  }

  /**
   * Cleanup method, on service start, we'll get rid of old files.
   */
  @Override
  public void afterPropertiesSet() {
    cleanUnusedFilesFromCache();
  }

  private void cleanUnusedFilesFromCache() {
    try (Stream<Path> pathElements = Files.walk(this.cacheDirectory)) {
      pathElements
          .filter(Files::isRegularFile)
          .forEach(this::deleteCachedFileIfNeeded);
    } catch (IOException e) {
      log.error("Cleaning featured mod files cache failed", e);
    }
  }

  /**
   * Per directory cleanup old files.
   */
  private void deleteCachedFileIfNeeded(Path filePath) {
    try {
      ResourceLocks.acquireDiskLock();

      FileTime lastAccessTime = Files.readAttributes(filePath, BasicFileAttributes.class).lastAccessTime();
      OffsetDateTime comparableLastAccessTime = OffsetDateTime.ofInstant(lastAccessTime.toInstant(), ZoneId.systemDefault());
      if (comparableLastAccessTime.plusDays(this.cacheLifeTimeInDays).isBefore(OffsetDateTime.now())) {
        log.info("Deleting cached file ''{}'' (last access:", filePath.toString());
        Files.deleteIfExists(filePath);
      }
    } catch (IOException e) {
      log.error("Exception during deleting the cache files", e);
    } finally {
      ResourceLocks.freeDiskLock();
    }
  }
}
