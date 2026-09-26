#include "app/AppState.hpp"
#include <iostream>
int main() {
    memory::AppState app;
    memory::Event lab;
    lab.id = "lab";
    lab.title = "Programming Lab";
    lab.location = "Room 3-5";
    lab.start = 30000840;
    lab.end = lab.start + 120;
    lab.repeatDays = 7;
    lab.items = {{"charger", "Charger", 1.0}, {"laptop", "Laptop", .9}};
    const auto result = app.Execute({{"action", "save_event"}, {"event", lab}, {"now", lab.start - 60}});
    std::cout << result.at("view").dump(2) << '\n';
}
