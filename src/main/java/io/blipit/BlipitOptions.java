package io.blipit;

public final class BlipitOptions {
    public final String key;
    public final String project;
    public final String environment;
    public final String release;
    public final double tracesSampleRate;
    public final String endpoint;
    public final java.io.File cacheDir;

    private BlipitOptions(Builder b) {
        key = b.key;
        project = b.project;
        environment = b.environment;
        release = b.release;
        tracesSampleRate = b.tracesSampleRate;
        endpoint = b.endpoint;
        cacheDir = b.cacheDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String key;
        private String project;
        private String environment;
        private String release;
        private double tracesSampleRate = 0.0;
        private String endpoint = Blipit.DEFAULT_ENDPOINT;
        private java.io.File cacheDir;

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder project(long project) {
            this.project = Long.toString(project);
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder release(String release) {
            this.release = release;
            return this;
        }

        public Builder tracesSampleRate(double tracesSampleRate) {
            this.tracesSampleRate = tracesSampleRate;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder cacheDir(java.io.File cacheDir) {
            this.cacheDir = cacheDir;
            return this;
        }

        public BlipitOptions build() {
            return new BlipitOptions(this);
        }
    }
}
