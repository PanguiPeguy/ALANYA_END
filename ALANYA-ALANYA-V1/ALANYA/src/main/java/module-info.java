module com.alanya.bd.alanya {
    requires javafx.controls;
    requires javafx.fxml;
    //requires javafx.web;
    requires java.sql;
    requires javafx.media;
    requires java.desktop;
    requires Java.WebSocket;
    requires org.slf4j;
    requires javafx.swing;
    requires webcam.capture;

    opens com.alanya.bd to javafx.fxml;
    exports Client to javafx.graphics;
    exports Client.service to javafx.graphics;

    exports com.alanya.bd;
}