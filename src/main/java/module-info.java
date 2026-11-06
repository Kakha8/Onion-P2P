module kakha.kudava.onionp2p {
    requires javafx.controls;
    requires javafx.fxml;
    requires jdk.httpserver;


    opens kakha.kudava.onionp2p to javafx.fxml;
    exports kakha.kudava.onionp2p;
}
