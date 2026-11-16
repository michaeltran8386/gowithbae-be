package vn.gowithbae;

import org.springframework.boot.SpringApplication;

public class TestGowithbaeBeApplication {

	public static void main(String[] args) {
		SpringApplication.from(GowithbaeBeApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
