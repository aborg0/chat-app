package com.example.models

case class User(id: String, username: String, passwordHash: String, email: Option[String], devices: List[Device])
case class Device(id: String, name: String, lastActive: Long)
case class Chapter(id: String, title: String, parentId: Option[String], sharedWith: List[String])
case class Group(id: String, name: String, members: List[String])
case class Message(id: String, senderId: String, groupId: String, content: String, timestamp: Long)
case class Notification(id: String, userId: String, messageId: String, read: Boolean)