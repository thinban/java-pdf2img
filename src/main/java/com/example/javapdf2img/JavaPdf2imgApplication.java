package com.example.javapdf2img;

import com.spire.pdf.PdfDocument;
import com.spire.pdf.PdfPageBase;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SpringBootApplication
@Controller
@Slf4j
@CrossOrigin
public class JavaPdf2imgApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaPdf2imgApplication.class, args);
    }

    /**
     * 递归加入zip
     *
     * @param directory
     * @param baseName
     * @param zos
     * @throws IOException
     */
    private static void zip(File directory, String baseName, ZipOutputStream zos) throws IOException {
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                zip(file, baseName + "/" + file.getName(), zos);
            } else {
                byte[] buffer = new byte[1024];
                FileInputStream fis = new FileInputStream(file);
                zos.putNextEntry(new ZipEntry(baseName + "/" + file.getName()));
                int length;
                while ((length = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, length);
                }
                zos.closeEntry();
                fis.close();
            }
        }
    }

    /**
     * 递归删除临时目录
     *
     * @param tempDirPath
     */
    public static void deleteTempDir(String tempDirPath) {
        log.info("pdf2Img删除临时目录及文件:{}", tempDirPath);
        File tempDir = new File(tempDirPath);
        if (tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteTempDir(file.getAbsolutePath()); // 递归删除子目录
                    } else {
                        file.delete(); // 删除文件
                    }
                }
            }
            tempDir.delete(); // 删除目录
        }
    }


    /**
     * pdf2Img
     *
     * @param file pdf文件
     * @return 转化后的图片.zip
     */
    @PostMapping("pdf2Img")
    public Object pdf2Img(@RequestParam MultipartFile file) {
        try {
            String fn = file.getOriginalFilename();
            fn = fn.replaceAll(".pdf", "_");
            long now = System.currentTimeMillis();

            PdfDocument pdf = new PdfDocument();
            pdf.loadFromStream(file.getInputStream());

            String zipFilePath = fn + now + ".zip";
            log.info("pdf2Img加载pdf:{},期望输出:{}", fn, zipFilePath);

            String tempDirPath = null;

            int index = 0;
            for (int i = 0; i < pdf.getPages().getCount(); i++) {
                //获取PDF页面
                PdfPageBase page = pdf.getPages().get(i);
                //使用extractImages方法获取页面上图片
                if (page.extractImages() != null) {

                    if (StringUtils.isEmpty(tempDirPath)) {
                        tempDirPath = Files.createTempDirectory(fn + now).toString();
                    }

                    for (BufferedImage image : page.extractImages()) {
                        //指定输出图片名称
                        File output = new File(tempDirPath + "/" + String.format("fn_%d.png", index++));
                        //将图片保存为PNG格式文件
                        ImageIO.write(image, "PNG", output);
                    }
                } else {
                    log.info("{} 未发现图片。", fn);
                    return ResponseEntity.status(500).body(fn + "未检测到图片");
                }
            }
            log.info("pdf2Img提取图片完成: {},临时目录:{}", fn, tempDirPath);

            // 执行完毕后将临时目录打包成zip文件
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);
            zip(new File(tempDirPath), zipFilePath, zos);
            zos.close();
            baos.close();

            // 自动删除临时目录
            deleteTempDir(tempDirPath);

            byte[] zipBytes = baos.toByteArray();
            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Disposition", "attachment; filename=" + zipFilePath);
            return ResponseEntity.ok().headers(headers).body(zipBytes);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("服务不可用: " + e.getMessage());
        }
    }
}
