package com.example.bookgarden.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.bson.types.ObjectId;
import org.springframework.web.multipart.MultipartFile;

@Data
public class PostCreateRequestDTO {
    @NotEmpty(message = "Tiêu đề bài viết không được bỏ trống")
    private String title;
    @NotEmpty(message = "Nội dung bài viết không được bỏ trống")
    private String content;
    private String bookId;
    private MultipartFile image;
    private Boolean rejected_FE;

}
