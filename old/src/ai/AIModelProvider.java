package ai;

public interface AIModelProvider {
    String[] listModels();
    String send(String model, String prompt, String context) throws Exception;
}

