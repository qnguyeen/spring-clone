package com.project.shopapp.controllers;

import com.project.shopapp.components.SecurityUtils;
import com.project.shopapp.exceptions.DataNotFoundException;
import com.project.shopapp.exceptions.InvalidPasswordException;
import com.project.shopapp.models.Token;
import com.project.shopapp.models.User;
import com.project.shopapp.responses.ResponseObject;
import com.project.shopapp.responses.user.LoginResponse;
import com.project.shopapp.responses.user.UserListResponse;
import com.project.shopapp.responses.user.UserResponse;
import com.project.shopapp.services.token.ITokenService;
import com.project.shopapp.services.user.IUserService;
import com.project.shopapp.components.LocalizationUtils;
import com.project.shopapp.utils.FileUtils;
import com.project.shopapp.utils.MessageKeys;
import com.project.shopapp.utils.ValidationUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import com.project.shopapp.dtos.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${api.prefix}/users")
@RequiredArgsConstructor

public class UserController {
    private final IUserService userService;
    private final LocalizationUtils localizationUtils;
    private final ITokenService tokenService;
    private final SecurityUtils securityUtils;

    @GetMapping("")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ResponseObject> getAllUser(
            @RequestParam(defaultValue = "", required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int limit
    ) throws Exception{
        // Tạo Pageable từ thông tin trang và giới hạn
        PageRequest pageRequest = PageRequest.of(
                page, limit,
                //Sort.by("createdAt").descending()
                Sort.by("id").ascending()
        );
        Page<UserResponse> userPage = userService.findAll(keyword, pageRequest)
                .map(UserResponse::fromUser);

        // Lấy tổng số trang
        int totalPages = userPage.getTotalPages();
        List<UserResponse> userResponses = userPage.getContent();
        UserListResponse userListResponse = UserListResponse
                .builder()
                .users(userResponses)
                .totalPages(totalPages)
                .build();
        return ResponseEntity.ok().body(ResponseObject.builder()
                        .message("Get user list successfully")
                        .status(HttpStatus.OK)
                        .data(userListResponse)
                .build());
    }
    @PostMapping("/register")
    //can we register an "admin" user ?
    public ResponseEntity<ResponseObject> createUser(
            @Valid @RequestBody UserDTO userDTO,
            BindingResult result
    ) throws Exception {
        if (result.hasErrors()) {
            List<String> errorMessages = result.getFieldErrors()
                    .stream()
                    .map(FieldError::getDefaultMessage)
                    .toList();

            return ResponseEntity.badRequest().body(ResponseObject.builder()
                    .status(HttpStatus.BAD_REQUEST)
                    .data(null)
                    .message(errorMessages.toString())
                    .build());
        }
        if (userDTO.getEmail() == null || userDTO.getEmail().trim().isBlank()) {
            if (userDTO.getPhoneNumber() == null || userDTO.getPhoneNumber().isBlank()) {
                return ResponseEntity.badRequest().body(ResponseObject.builder()
                        .status(HttpStatus.BAD_REQUEST)
                        .data(null)
                        .message("At least email or phone number is required")
                        .build());
            } else {
                //phone number not blank
                if (!ValidationUtils.isValidPhoneNumber(userDTO.getPhoneNumber())) {
                    throw new Exception("Invalid phone number");
                }
            }
        } else {
            //Email not blank
            if (!ValidationUtils.isValidEmail(userDTO.getEmail())) {
                throw new Exception("Invalid email format");
            }
        }

        if (!userDTO.getPassword().equals(userDTO.getRetypePassword())) {
            //registerResponse.setMessage();
            return ResponseEntity.badRequest().body(ResponseObject.builder()
                    .status(HttpStatus.BAD_REQUEST)
                    .data(null)
                    .message(localizationUtils.getLocalizedMessage(MessageKeys.PASSWORD_NOT_MATCH))
                    .build());
        }
        User user = userService.createUser(userDTO);
        return ResponseEntity.ok(ResponseObject.builder()
                .status(HttpStatus.CREATED)
                .data(UserResponse.fromUser(user))
                .message("Account registration successful")
                .build());
    }

    @PostMapping("/login")
    public ResponseEntity<ResponseObject> login(
            @Valid @RequestBody UserLoginDTO userLoginDTO,
            HttpServletRequest request
    ) throws Exception {
        // Kiểm tra thông tin đăng nhập và sinh token
        String token = userService.login(userLoginDTO);
        String userAgent = request.getHeader("User-Agent");
        User userDetail = userService.getUserDetailsFromToken(token);
        Token jwtToken = tokenService.addToken(userDetail, token, isMobileDevice(userAgent));

        LoginResponse loginResponse = LoginResponse.builder()
                .message(localizationUtils.getLocalizedMessage(MessageKeys.LOGIN_SUCCESSFULLY))
                .token(jwtToken.getToken())
                .tokenType(jwtToken.getTokenType())
                .refreshToken(jwtToken.getRefreshToken())
                .username(userDetail.getUsername())
                .roles(userDetail.getAuthorities().stream().map(item -> item.getAuthority()).toList())
                .id(userDetail.getId())
                .build();
        return ResponseEntity.ok().body(ResponseObject.builder()
                        .message("Login successfully")
                        .data(loginResponse)
                        .status(HttpStatus.OK)
                .build());
    }
    @PostMapping("/refreshToken")
    public ResponseEntity<ResponseObject> refreshToken(
            @Valid @RequestBody RefreshTokenDTO refreshTokenDTO
    ) throws Exception {
        User userDetail = userService.getUserDetailsFromRefreshToken(refreshTokenDTO.getRefreshToken());
        Token jwtToken = tokenService.refreshToken(refreshTokenDTO.getRefreshToken(), userDetail);
        LoginResponse loginResponse = LoginResponse.builder()
                .message("Refresh token successfully")
                .token(jwtToken.getToken())
                .tokenType(jwtToken.getTokenType())
                .refreshToken(jwtToken.getRefreshToken())
                .username(userDetail.getUsername())
                .roles(userDetail.getAuthorities().stream().map(item -> item.getAuthority()).toList())
                .id(userDetail.getId()).build();
        return ResponseEntity.ok().body(
                ResponseObject.builder()
                        .data(loginResponse)
                        .message(loginResponse.getMessage())
                        .status(HttpStatus.OK)
                        .build());

    }
    private boolean isMobileDevice(String userAgent) {
        // Kiểm tra User-Agent header để xác định thiết bị di động
        // Ví dụ đơn giản:
        return userAgent.toLowerCase().contains("mobile");
    }
    @PostMapping("/details")
    @PreAuthorize("hasRole('ROLE_ADMIN') or hasRole('ROLE_USER')")
    public ResponseEntity<ResponseObject> getUserDetails(
            @RequestHeader("Authorization") String authorizationHeader
    ) throws Exception {
        String extractedToken = authorizationHeader.substring(7); // Loại bỏ "Bearer " từ chuỗi token
        User user = userService.getUserDetailsFromToken(extractedToken);
        return ResponseEntity.ok().body(
                ResponseObject.builder()
                        .message("Get user's detail successfully")
                        .data(UserResponse.fromUser(user))
                        .status(HttpStatus.OK)
                        .build()
        );
    }
    @PutMapping("/details/{userId}")
    @PreAuthorize("hasRole('ROLE_ADMIN') or hasRole('ROLE_USER')")
    @Operation(security = { @SecurityRequirement(name = "bearer-key") })
    public ResponseEntity<ResponseObject> updateUserDetails(
            @PathVariable Long userId,
            @RequestBody UpdateUserDTO updatedUserDTO,
            @RequestHeader("Authorization") String authorizationHeader
    ) throws Exception{
        String extractedToken = authorizationHeader.substring(7);
        User user = userService.getUserDetailsFromToken(extractedToken);
        // Ensure that the user making the request matches the user being updated
        if (user.getId() != userId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        User updatedUser = userService.updateUser(userId, updatedUserDTO);
        return ResponseEntity.ok().body(
                ResponseObject.builder()
                        .message("Update user detail successfully")
                        .data(UserResponse.fromUser(updatedUser))
                        .status(HttpStatus.OK)
                        .build()
        );
    }
    @PutMapping("/reset-password/{userId}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ResponseObject> resetPassword(@Valid @PathVariable long userId){
        try {
            String newPassword = UUID.randomUUID().toString().substring(0, 5); // Tạo mật khẩu mới
            userService.resetPassword(userId, newPassword);
            return ResponseEntity.ok(ResponseObject.builder()
                            .message("Reset password successfully")
                            .data(newPassword)
                            .status(HttpStatus.OK)
                    .build());
        } catch (InvalidPasswordException e) {
            return ResponseEntity.ok(ResponseObject.builder()
                    .message("Invalid password")
                    .data("")
                    .status(HttpStatus.BAD_REQUEST)
                    .build());
        } catch (DataNotFoundException e) {
            return ResponseEntity.ok(ResponseObject.builder()
                    .message("User not found")
                    .data("")
                    .status(HttpStatus.BAD_REQUEST)
                    .build());
        }
    }
    @PutMapping("/block/{userId}/{active}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ResponseObject> blockOrEnable(
            @Valid @PathVariable long userId,
            @Valid @PathVariable int active
    ) throws Exception {
        userService.blockOrEnable(userId, active > 0);
        String message = active > 0 ? "Successfully enabled the user." : "Successfully blocked the user.";
        return ResponseEntity.ok().body(ResponseObject.builder()
                .message(message)
                .status(HttpStatus.OK)
                .data(null)
                .build());
    }

    @PostMapping(value = "/upload-profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_ADMIN')")
    public ResponseEntity<ResponseObject> uploadProfileImage(
            @RequestParam("file") MultipartFile file
    ) throws Exception {
        User loginUser = securityUtils.getLoggedInUser();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    ResponseObject.builder()
                            .message("Image file is required.")
                            .build()
            );
        }

        if (file.getSize() > 10 * 1024 * 1024) { // 10MB
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ResponseObject.builder()
                            .message("Image file size exceeds the allowed limit of 10MB.")
                            .status(HttpStatus.PAYLOAD_TOO_LARGE)
                            .build());
        }

        // Check file type
        if (!FileUtils.isImageFile(file)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ResponseObject.builder()
                            .message("Uploaded file must be an image.")
                            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                            .build());
        }

        // Store file and get filename
        String oldFileName = loginUser.getProfileImage();
        String imageName = FileUtils.storeFile(file);

        userService.changeProfileImage(loginUser.getId(), imageName);
        // Delete old file if exists
        if (!StringUtils.isEmpty(oldFileName)) {
            FileUtils.deleteFile(oldFileName);
        }
//1aba82e1-4599-4c8b-8ec5-9c16e5aad379_3734888057500.png
        return ResponseEntity.ok().body(ResponseObject.builder()
                .message("Upload profile image successfully")
                .status(HttpStatus.CREATED)
                .data(imageName) // Return the filename or image URL
                .build());
    }
    @GetMapping("/profile-images/{imageName}")
    public ResponseEntity<?> viewImage(@PathVariable String imageName) {
        try {
            java.nio.file.Path imagePath = Paths.get("uploads/"+imageName);
            UrlResource resource = new UrlResource(imagePath.toUri());

            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(resource);
            } else {
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(new UrlResource(Paths.get("uploads/default-profile-image.jpeg").toUri()));
                //return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
