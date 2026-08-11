package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.FileUrl;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse {
    private String id;
    private String name;
    private String email;
    private String profileImage;

    public static UserResponse from(User user) {
        String profileImageUrl = FileUrl.of(user.getProfileImage());
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .profileImage(profileImageUrl != null ? profileImageUrl : "")
                .build();
    }

    public static UserResponse from(SocketUser user) {
        String profileImageUrl = FileUrl.of(user.profileImage());
        return UserResponse.builder()
                .id(user.id())
                .name(user.name())
                .email(user.email())
                .profileImage(profileImageUrl != null ? profileImageUrl : "")
                .build();
    }
}
