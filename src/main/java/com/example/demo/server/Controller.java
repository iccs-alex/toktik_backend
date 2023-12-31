package com.example.demo.server;

import com.example.demo.MyUser;
import com.example.demo.Notif;
import com.example.demo.SubbedVideo;
import com.example.demo.UserRepository;
import com.example.demo.msgbroker.MessagePublisher;
import com.example.demo.server.VideoDetails;
import com.example.demo.server.VideoRepository;
import com.example.demo.NotifRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.HttpMethod;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisTemplate;

import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.json.simple.JSONObject;

@RestController
public class Controller {

    @Autowired
    MongoOperations mongoOperations;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    NotifRepository notifRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    String socketioChannel = "socketio";

    String s3BucketName = "toktik-videos";
    Regions region = Regions.AP_SOUTHEAST_1;

    @GetMapping("/api/video")
    public String getVideo(@RequestParam String key) {
        final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(region).build();

        URL url = s3.generatePresignedUrl(s3BucketName, key, new Date(new Date().getTime() + 100000), HttpMethod.GET);

        return url.toString();
    }

    @PutMapping("/api/video")
    public String putVideo(@RequestBody VideoDetails videoDetails) {
        try {
            VideoDetails _videoDetails = videoRepository.save(
                    new VideoDetails(videoDetails.getKey(), videoDetails.getUsername(), videoDetails.getTitle(),
                            videoDetails.getDescription()));
        } catch (Exception e) {
            System.out.println(e);
        }
        final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(region).build();
        URL url = s3.generatePresignedUrl(s3BucketName, "videos/" + videoDetails.getKey(),
                new Date(new Date().getTime() + 100000), HttpMethod.PUT);

        return url.toString();
    }

    @DeleteMapping("/api/video")
    public String deleteVideo(@RequestParam String key) {
        try {
            VideoDetails video_ = videoRepository.deleteByKey(key);
        } catch (Exception e) {
            System.out.println(e);
        }

        final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(region).build();

        URL url = s3.generatePresignedUrl(s3BucketName, key, new Date(new Date().getTime() + 100000),
                HttpMethod.DELETE);

        return url.toString();
    }

    @GetMapping("/api/videos")
    public List<VideoDetails> getAllVideos() {
        return videoRepository.findAll();
    }

    @GetMapping("/api/videos/user")
    public List<VideoDetails> getUserVideos(@RequestParam String username) {
        return videoRepository.findByUsername(username);
    }

    @GetMapping("/api/video/details")
    public VideoDetails getVideoDetails(@RequestParam String key) {
        return videoRepository.findByKey(key);
    }

    @PostMapping("/api/video/view")
    public Integer viewVideo(@RequestParam String key) {
        VideoDetails videoDetails_ = videoRepository.findByKey(key);
        Integer newViewCount = videoDetails_.getViewCount() + 1;
        videoDetails_.setViewCount(newViewCount);
        videoRepository.save(videoDetails_);

        JSONObject jsonObj = new JSONObject();
        jsonObj.put("action", "viewUpdate");
        String[] rooms = { "video:" + key, "home" };
        jsonObj.put("rooms", rooms);
        JSONObject jsonData = new JSONObject();
        jsonData.put("viewCount", newViewCount);
        jsonData.put("videoKey", key);
        jsonObj.put("data", jsonData);
        sendDataToChannel(socketioChannel, jsonObj);

        return newViewCount;
    }

    @PostMapping("/api/video/like")
    public Integer likeVideo(@RequestParam String key, @RequestParam String username) {

        // Update Like Count
        VideoDetails videoDetails_ = videoRepository.findByKey(key);
        Integer newLikeCount = videoDetails_.getLikeCount() + 1;
        videoDetails_.setLikeCount(newLikeCount);
        videoDetails_.getUserLikes().add(username);
        videoRepository.save(videoDetails_);
        System.out.println(videoRepository.findByKey(key).getUserLikes());

        // Send socketio like update
        JSONObject jsonObj = new JSONObject();
        jsonObj.put("action", "likeUpdate");
        String[] rooms = { "video:" + key, "home" };
        jsonObj.put("rooms", rooms);
        JSONObject jsonData = new JSONObject();
        jsonData.put("likeCount", newLikeCount);
        jsonData.put("videoKey", key);
        jsonObj.put("data", jsonData);
        sendDataToChannel(socketioChannel, jsonObj);

        addSubbedVideo(username, key);
        sendNotifs(key, username + " liked a video.", username);

        return newLikeCount;
    }

    @PostMapping("/api/video/unlike")
    public Integer unlikeVideo(@RequestParam String key, @RequestParam String username) {
        VideoDetails videoDetails_ = videoRepository.findByKey(key);
        Integer newLikeCount = videoDetails_.getLikeCount() - 1;
        videoDetails_.setLikeCount(newLikeCount);
        videoDetails_.getUserLikes().remove(username);
        videoRepository.save(videoDetails_);
        System.out.println(videoRepository.findByKey(key).getUserLikes());

        JSONObject jsonObj = new JSONObject();
        jsonObj.put("action", "likeUpdate");
        String[] rooms = { "video:" + key, "home" };
        jsonObj.put("rooms", rooms);
        JSONObject jsonData = new JSONObject();
        jsonData.put("likeCount", newLikeCount);
        jsonData.put("videoKey", key);
        jsonObj.put("data", jsonData);
        sendDataToChannel(socketioChannel, jsonObj);

        return newLikeCount;
    }

    @PostMapping("/api/video/comment")
    public Object comment(@RequestParam String key, @RequestParam String username, @RequestBody JSONObject comment) {

        // Add comment to video
        VideoDetails videoDetails_ = videoRepository.findByKey(key);
        System.out.println("The comment:");
        System.out.println(comment.get(comment));
        videoDetails_.getVideoComments().add(new VideoComment(username, (String) comment.get("comment")));
        videoRepository.save(videoDetails_);

        // Send socketio comment update
        JSONObject commentJson = new JSONObject();
        commentJson.put("action", "commentUpdate");
        String[] rooms = { "video:" + key };
        commentJson.put("rooms", rooms);
        sendDataToChannel(socketioChannel, commentJson);

        addSubbedVideo(username, key);
        sendNotifs(key, username + " commented on a video.", username);

        return comment.get("comment");
    }

    @GetMapping("/api/video/comments")
    public List<VideoComment> getComments(@RequestParam String key) {
        VideoDetails videoDetails_ = videoRepository.findByKey(key);

        return videoDetails_.videoComments;
    }

    @PostMapping("/api/notif")
    public Notif notif(@RequestParam String username, @RequestBody Notif notif) {
        MyUser user_ = userRepository.findFirstByUsername(username);
        System.out.println("The notif:");
        System.out.println(notif);
        user_.getNotifs().add(notif);
        userRepository.save(user_);

        JSONObject jsonObj = new JSONObject();
        jsonObj.put("action", "notifUpdate");
        String[] rooms = { "navbar" };
        jsonObj.put("rooms", rooms);
        sendDataToChannel(socketioChannel, jsonObj);

        return notif;
    }

    @DeleteMapping("/api/notif")
    public List<Notif> deleteNotif(@RequestParam String username, @RequestParam Long notifId) {
        MyUser user_ = userRepository.findFirstByUsername(username);
        user_.removeNotif(notifId);
        userRepository.save(user_);
        return userRepository.findFirstByUsername(username).getNotifs();
    }

    @GetMapping("/api/notifs")
    public List<Notif> getNotifs(@RequestParam String username) {
        MyUser user_ = userRepository.findFirstByUsername(username);

        return user_.getNotifs();
    }

    private void addSubbedVideo(String username, String key) {
        MyUser user_ = userRepository.findFirstByUsername(username);
        if (user_.getSubbedVideos().contains(key)) {
            return;
        }

        user_.getSubbedVideos().add(key);
        userRepository.save(user_);
    }

    private void sendNotifs(String key, String notifMessage, String username) {
        List<MyUser> subbedUsers = userRepository.findAllUsersBySubbedVideo(key);

        for (MyUser subbedUser : subbedUsers) {
            if (subbedUser.getUsername().equals(username))
                continue;
            System.out.println(subbedUser.getUsername());
            Notif notif = new Notif(notifMessage, key, subbedUser);
            subbedUser.addNotif(notif);
            System.out.println(subbedUser.getNotifs().get(subbedUser.getNotifs().size() - 1).getMessage());
            userRepository.save(subbedUser);
            System.out.println(userRepository.findFirstByUsername(subbedUser.getUsername()).getNotifs()
                    .get(userRepository.findFirstByUsername(subbedUser.getUsername()).getNotifs().size() - 1)
                    .getMessage());
        }

        JSONObject notifJson = new JSONObject();
        notifJson.put("action", "notifUpdate");
        String[] rooms = { "notifComment:" + key };
        notifJson.put("rooms", rooms);
        sendDataToChannel(socketioChannel, notifJson);

    }

    public void sendDataToChannel(String channel, JSONObject data) {
        redisTemplate.convertAndSend(channel, data);
    }

}
