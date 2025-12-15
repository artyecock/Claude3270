package ai;

public class OllamaProvider implements AIModelProvider {

    private final String endpoint;
    private final String[] models;

    public OllamaProvider(String endpoint, String[] models) {
        this.endpoint = endpoint;
        this.models = models;
    }

    @Override
    public String[] listModels() {
        return models;
    }

    @Override
    public String send(String model, String prompt, String context) throws Exception {
        // NOT IMPLEMENTED — placeholder
        return "Local model support not implemented yet.\nModel: " + model;
    }
}

