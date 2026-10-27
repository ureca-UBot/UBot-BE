package com.ubot.user.entity;

import com.ubot.user.enums.Gender;
import com.ubot.user.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name="users")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class User {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	private Long id;

	@Column(name = "email")
	private String email;

	@Column(name = "password_hash")
	private String hashedPassword;

	@Column(name = "name")
	private String name;

	@Column(name = "birth_date")
	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "gender")
	private Gender gender;

	@Column(name = "residence_area")
	private String residenceArea;

	@Enumerated(EnumType.STRING)
	@Column(name = "role")
	private UserRole role;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public void update(String name, LocalDate birthDate, Gender gender, String residenceArea){
		if(name != null){
			this.name = name.trim();
		}
		if(birthDate != null){
			this.birthDate = birthDate;
		}
		if(gender != null){
			this.gender = gender;
		}
		if(residenceArea != null){
			this.residenceArea = residenceArea.trim();
		}
		this.updatedAt = LocalDateTime.now();
	}
}
