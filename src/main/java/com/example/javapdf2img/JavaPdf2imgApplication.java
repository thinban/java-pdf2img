package com.example.javapdf2img;

import com.spire.pdf.PdfDocument;
import com.spire.pdf.PdfPageBase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * 临时目录打包成zip，并导出文件
     *
     * @param destinationDir
     * @param zipFilePath
     * @return
     */
    public ResponseEntity renderZip(String destinationDir, String zipFilePath) {
        try {
            // 执行完毕后将临时目录打包成zip文件
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);
            zip(new File(destinationDir), zipFilePath, zos);
            zos.close();
            baos.close();

            // 自动删除临时目录
            deleteTempDir(destinationDir);

            byte[] zipBytes = baos.toByteArray();
            HttpHeaders headers = new HttpHeaders();
            zipFilePath = zipFilePath.substring(zipFilePath.indexOf("_") + 1);
            headers.add("Content-Disposition", "attachment; filename=pdf2img" + zipFilePath);
            return ResponseEntity.ok().headers(headers).body(zipBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
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
     * 提取PDF中的图片
     *
     * @param file pdf文件
     * @return 转化后的图片.zip
     */
    @PostMapping("pdf2Img")
    public Object pdf2Img(@RequestParam MultipartFile file) {
        try {
            String fn = file.getOriginalFilename();
            String fnNoExt = fn.replaceAll(".pdf", "_");
            long now = System.currentTimeMillis();

            PdfDocument pdf = new PdfDocument();
            pdf.loadFromStream(file.getInputStream());

            String zipFilePath = fnNoExt + now + ".zip";
            log.info("pdf2Img加载pdf:{},期望输出:{}", fn, zipFilePath);

            String tempDirPath = null;

            int index = 0;
            for (int i = 0; i < pdf.getPages().getCount(); i++) {
                //获取PDF页面
                PdfPageBase page = pdf.getPages().get(i);
                //使用extractImages方法获取页面上图片
                if (page.extractImages() != null) {

                    if (StringUtils.isEmpty(tempDirPath)) {
                        tempDirPath = Files.createTempDirectory(fnNoExt + now).toString();
                    }

                    for (BufferedImage image : page.extractImages()) {
                        //指定输出图片名称
                        File output = new File(tempDirPath + "/" + String.format("%s_%d.png", fnNoExt, index++));
                        //将图片保存为PNG格式文件
                        ImageIO.write(image, "PNG", output);
                    }
                } else {
                    log.info("{} 未发现图片。", fn);
                    return ResponseEntity.status(500).body(fn + "未检测到图片");
                }
            }
            log.info("pdf2Img提取图片完成: {},临时目录:{}", fn, tempDirPath);

            return renderZip(tempDirPath, zipFilePath);

        } catch (IOException e) {
            return ResponseEntity.status(500).body("服务不可用: " + e.getMessage());
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class SaveImgJob implements Runnable {
        BufferedImage bim;
        File outputfile;
        CountDownLatch latch;

        @Override
        public void run() {
            try {
                log.info("图片已创建 -> " + outputfile.getName());
                ImageIO.write(bim, "png", outputfile);
                latch.countDown();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


    /**
     * PDF按页转成图片
     *
     * @param file
     * @return
     */
    @PostMapping("pdf2ImgV2")
    public Object pdf2ImgV2(@RequestParam MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            String fnNoExt = originalFilename.replaceAll(".pdf", "_");
            long now = System.currentTimeMillis();
            String destinationDir = Files.createTempDirectory(fnNoExt + now).toString();
            String zipFilePath = fnNoExt + now + ".zip";

            File destinationFile = new File(destinationDir);
            if (!destinationFile.exists()) {
                destinationFile.mkdir();
                log.info("文件夹已创建 -> " + destinationFile.getAbsolutePath());
            }

            log.info("图片复制到文件夹: " + destinationFile.getName());


            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFRenderer pdfRenderer = new PDFRenderer(document);
                ExecutorService executor = Executors.newFixedThreadPool(10);
                CountDownLatch latch = new CountDownLatch(document.getNumberOfPages());
                for (int page = 0; page < document.getNumberOfPages(); ++page) {
                    BufferedImage bim = pdfRenderer.renderImage(page);
                    File outputfile = new File(destinationDir + "/" + fnNoExt + "_" + (page + 1) + ".png");
                    // 提交I/O任务到线程池
                    executor.submit(SaveImgJob.builder().bim(bim).outputfile(outputfile).latch(latch).build());
                }
                // 等待所有I/O任务完成
                latch.await();
                executor.shutdown();

                log.info("转换后的图片保存在 -> " + destinationFile.getAbsolutePath());
                return renderZip(destinationDir, zipFilePath);

            } catch (IOException e) {
                return ResponseEntity.status(500).body("服务不可用: " + e.getMessage());
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body("服务不可用: " + e.getMessage());
        }
    }


}
