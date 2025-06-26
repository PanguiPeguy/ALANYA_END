CREATE DATABASE chat_application;
use chat_application;

-- ----------------------------
-- Table structure for files
-- ----------------------------
DROP TABLE IF EXISTS `files`;
CREATE TABLE `files` (
  `FileID` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `FileExtension` varchar(255) DEFAULT NULL,
  `BlurHash` varchar(255) DEFAULT NULL,
  `Status` char(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`FileID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of files
-- ----------------------------

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `UserID` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `UserName` varchar(255) DEFAULT NULL,
  `Password` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`UserID`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of user
-- ----------------------------
INSERT INTO `user` VALUES ('36', 'dara', '123');
INSERT INTO `user` VALUES ('37', 'raven', '123');
INSERT INTO `user` VALUES ('38', 'china', '123');

-- ----------------------------
-- Table structure for user_account
-- ----------------------------
DROP TABLE IF EXISTS `user_account`;
CREATE TABLE `user_account` (
  `UserID` int(10) unsigned NOT NULL,
  `UserName` varchar(255) DEFAULT NULL,
  `Gender` char(1) NOT NULL DEFAULT '',
  `Image` longblob,
  `ImageString` varchar(255) DEFAULT '',
  `Status` char(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`UserID`),
  CONSTRAINT `user_account_ibfk_1` FOREIGN KEY (`UserID`) REFERENCES `user` (`UserID`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `messages` (
    `message_id` INT AUTO_INCREMENT PRIMARY KEY,
    `sender_id` INT NOT NULL,
    `receiver_id` INT NOT NULL,
    `content` TEXT NOT NULL,
    `message_type` ENUM('TEXT', 'VOICE') NOT NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `is_read` BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (sender_id) REFERENCES user(UserID) ,
    FOREIGN KEY (receiver_id) REFERENCES user(UserID) 
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Table des salles (pour appels vidéo/audio)
CREATE TABLE IF NOT EXISTS `rooms` (
    `room_id` INT AUTO_INCREMENT PRIMARY KEY,
    `room_name` VARCHAR(100) NOT NULL UNIQUE,
    `created_by` INT NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    FOREIGN KEY (created_by) REFERENCES user(UserID) 
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Table des appels
CREATE TABLE IF NOT EXISTS `calls` (
    `call_id`INT AUTO_INCREMENT PRIMARY KEY,
    `room_id` INT NOT NULL,
    `caller_id` INT NOT NULL,
    `receiver_id` INT NOT NULL,
    `call_type` ENUM('VIDEO', 'AUDIO', 'VOICE') NOT NULL,
    `start_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `end_time` TIMESTAMP NULL,
    status ENUM('INITIATED', 'CONNECTED', 'ENDED', 'FAILED') DEFAULT 'INITIATED',
    FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    FOREIGN KEY (caller_id) REFERENCES user(UserID) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES user(UserID) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

DROP TABLE IF EXISTS `group_members`;
DROP TABLE IF EXISTS `groups`;
CREATE TABLE `groups` (
    `group_id` INT(10) unsigned AUTO_INCREMENT,
    `group_name` VARCHAR(100) NOT NULL UNIQUE,
    `created_by` int(10) unsigned NOT NULL ,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`group_id`),
    FOREIGN KEY (`created_by`) REFERENCES `user`(`UserID`) 
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8;


CREATE TABLE `group_members` (
  `group_id` int(10) unsigned NOT NULL,
  `user_id` int(10) unsigned NOT NULL,
  `joined_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`group_id`, `user_id`),
  FOREIGN KEY (group_id) REFERENCES `groups`(`group_id`),
  FOREIGN KEY (user_id) REFERENCES `user`(`userID`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8;

-- Ajouter une colonne temporaire pour marquer les mots de passe migrés
ALTER TABLE user ADD COLUMN is_migrated BOOLEAN DEFAULT FALSE;

-- Mettre à jour les mots de passe non migrés
-- Remplacez 'your_password' par le mot de passe en clair correspondant à chaque utilisateur
UPDATE user 
SET Password = '$2a$10$...hachage_bcrypt...' 
WHERE UserName = 'raven' AND Password = '123' AND is_migrated = FALSE;

-- Marquer comme migré
UPDATE user SET is_migrated = TRUE WHERE UserName = 'raven';

-- Supprimer la colonne temporaire après migration
ALTER TABLE user DROP COLUMN is_migrated;

-- ----------------------------
-- Records of user_account
-- ----------------------------
INSERT INTO `user_account` VALUES ('36', 'dara', '', null, '', '1');
INSERT INTO `user_account` VALUES ('37', 'raven', '', null, '', '1');
INSERT INTO `user_account` VALUES ('38', 'china', '', null, '', '1');

select * from user;
