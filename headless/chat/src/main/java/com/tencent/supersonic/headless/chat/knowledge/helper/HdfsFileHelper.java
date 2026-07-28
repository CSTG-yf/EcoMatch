package com.tencent.supersonic.headless.chat.knowledge.helper;

import com.hankcs.hanlp.HanLP.Config;
import com.hankcs.hanlp.dictionary.DynamicCustomDictionary;
import com.hankcs.hanlp.utility.Predefine;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Hdfs File Helper */
@Slf4j
public class HdfsFileHelper {

    /**
     * * delete cache file
     *
     * @param path
     * @throws IOException
     */
    public static void deleteCacheFile(String[] path) throws IOException {
        FileSystem fs = FileSystem.get(URI.create(path[0]), new Configuration());
        String cacheFilePath = path[0] + Predefine.BIN_EXT;
        log.info("Delete HDFS cache file=[{}]", SensitiveLogUtils.summarize(cacheFilePath));
        try {
            fs.delete(new Path(cacheFilePath), false);
        } catch (Exception e) {
            log.error("HDFS cache file deletion failed: file=[{}], type={}, error=[{}]",
                    SensitiveLogUtils.summarize(cacheFilePath), e.getClass().getSimpleName(),
                    SensitiveLogUtils.summarize(e));
        }
        int customBase = cacheFilePath.lastIndexOf(FileHelper.FILE_SPILT);
        String customPath =
                cacheFilePath.substring(0, customBase) + FileHelper.FILE_SPILT + "*.bin";
        List<String> fileList = getFileList(fs, new Path(customPath));
        for (String file : fileList) {
            try {
                fs.delete(new Path(file), false);
                log.info("Delete HDFS cache file=[{}]", SensitiveLogUtils.summarize(file));
            } catch (Exception e) {
                log.error("HDFS file deletion failed: file=[{}], type={}, error=[{}]",
                        SensitiveLogUtils.summarize(file), e.getClass().getSimpleName(),
                        SensitiveLogUtils.summarize(e));
            }
        }
        log.info("HDFS file list=[{}]", SensitiveLogUtils.summarize(fileList));
    }

    /**
     * reset path
     *
     * @param customDictionary
     * @throws IOException
     */
    public static void resetCustomPath(DynamicCustomDictionary customDictionary)
            throws IOException {
        String[] path = Config.CustomDictionaryPath;
        FileSystem fs = FileSystem.get(URI.create(path[0]), new Configuration());
        String cacheFilePath = path[0] + Predefine.BIN_EXT;

        Path hdfsPath = new Path(cacheFilePath);
        String parentPath = hdfsPath.getParent().toString();
        Path customPath = new Path(parentPath, "*.txt");
        log.info("HDFS custom path=[{}]", SensitiveLogUtils.summarize(customPath));
        List<String> fileList = getFileList(fs, customPath);
        log.info("HDFS custom dictionary paths=[{}]", SensitiveLogUtils.summarize(fileList));
        Config.CustomDictionaryPath = fileList.toArray(new String[0]);
        customDictionary.path =
                (Config.CustomDictionaryPath == null || Config.CustomDictionaryPath.length == 0)
                        ? path
                        : Config.CustomDictionaryPath;
        if (Config.CustomDictionaryPath == null || Config.CustomDictionaryPath.length == 0) {
            Config.CustomDictionaryPath = path;
        }
    }

    public static List<String> getFileList(FileSystem fs, Path folderPath) throws IOException {
        List<String> paths = new ArrayList();
        FileStatus[] fileStatuses = fs.globStatus(folderPath);
        for (int i = 0; i < fileStatuses.length; i++) {
            FileStatus fileStatus = fileStatuses[i];
            paths.add(fileStatus.getPath().toString());
        }
        return paths;
    }
}
