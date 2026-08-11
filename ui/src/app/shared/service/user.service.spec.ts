import { TestBed } from "@angular/core/testing";
import { ModalController } from "@ionic/angular";
import { TranslateService } from "@ngx-translate/core";
import { Theme } from "src/app/edge/history/shared";
import { User } from "../jsonrpc/shared";
import { Service } from "./service";
import { UserService } from "./user.service";

describe("UserService", () => {
    let modalController: jasmine.SpyObj<ModalController>;
    let service: jasmine.SpyObj<Service>;
    let userService: UserService;

    beforeEach(() => {
        localStorage.removeItem("THEME");
        localStorage.removeItem("THEME_COLOR");
        modalController = jasmine.createSpyObj<ModalController>("ModalController", ["create"]);
        service = jasmine.createSpyObj<Service>("Service", ["currentEdge", "toast"], {
            websocket: jasmine.createSpyObj("Websocket", ["sendRequest"]),
        });
        service.currentEdge.and.returnValue(null);

        TestBed.configureTestingModule({
            providers: [
                UserService,
                { provide: ModalController, useValue: modalController },
                { provide: Service, useValue: service },
                {
                    provide: TranslateService,
                    useValue: jasmine.createSpyObj<TranslateService>("TranslateService", ["instant"]),
                },
            ],
        });
        userService = TestBed.inject(UserService);
    });

    afterEach(() => {
        localStorage.removeItem("THEME");
        localStorage.removeItem("THEME_COLOR");
    });

    it("uses the system theme as the default", () => {
        expect(UserService.DEFAULT_THEME).toBe(Theme.SYSTEM);
    });

    it("keeps a selected theme locally when Backend persistence is unavailable", async () => {
        localStorage.setItem("THEME", Theme.LIGHT);
        userService.currentUser.set(new User("user", "User", "admin", "en", false, { useNewUI: true }));

        await userService.selectTheme(Theme.DARK);

        expect(localStorage.getItem("THEME")).toBe(Theme.DARK);
        expect(userService.currentUser()?.settings).toEqual({ useNewUI: true, theme: Theme.DARK });
    });

    it("falls back to the locally saved theme when the account has none", () => {
        localStorage.setItem("THEME", Theme.DARK);
        const user = new User("user", "User", "admin", "en", false, {});

        const getTheme = (userService as unknown as { getTheme(user: User): Theme | null }).getTheme.bind(userService);
        expect(getTheme(user)).toBe(Theme.DARK);
    });

    it("does not open duplicate theme modals", async () => {
        let dismissModal!: (value: { data: { selectedTheme: Theme } }) => void;
        const dismissed = new Promise<{ data: { selectedTheme: Theme } }>(resolve => dismissModal = resolve);
        const modal = jasmine.createSpyObj("HTMLIonModalElement", ["present", "onDidDismiss"]);
        modal.present.and.resolveTo();
        modal.onDidDismiss.and.returnValue(dismissed);
        modalController.create.and.resolveTo(modal);
        const user = new User("user", "User", "admin", "en", false, {});

        const showThemeSelection = (userService as unknown as { showThemeSelection(user: User): void }).showThemeSelection.bind(userService);
        showThemeSelection(user);
        showThemeSelection(user);

        expect(modalController.create).toHaveBeenCalledTimes(1);
        dismissModal!({ data: { selectedTheme: Theme.SYSTEM } });
        await dismissed;
    });
});
