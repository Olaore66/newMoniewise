--
-- PostgreSQL database dump
--

-- Dumped from database version 16.8
-- Dumped by pg_dump version 16.8

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: analytics_logs; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.analytics_logs (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    details jsonb,
    event_type character varying(255) NOT NULL,
    user_id bigint NOT NULL
);


ALTER TABLE public.analytics_logs OWNER TO postgres;

--
-- Name: analytics_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.analytics_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.analytics_logs_id_seq OWNER TO postgres;

--
-- Name: analytics_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.analytics_logs_id_seq OWNED BY public.analytics_logs.id;


--
-- Name: badges; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.badges (
    id bigint NOT NULL,
    active boolean NOT NULL,
    created_at timestamp with time zone,
    description character varying(255),
    icon_url character varying(255),
    name character varying(255) NOT NULL,
    threshold integer NOT NULL
);


ALTER TABLE public.badges OWNER TO postgres;

--
-- Name: badges_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.badges_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.badges_id_seq OWNER TO postgres;

--
-- Name: badges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.badges_id_seq OWNED BY public.badges.id;


--
-- Name: budgets; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.budgets (
    id bigint NOT NULL,
    allocated_amount numeric(19,2),
    created_at timestamp with time zone,
    duration_days integer NOT NULL,
    end_date date NOT NULL,
    name character varying(255) NOT NULL,
    start_date date NOT NULL,
    status character varying(255) NOT NULL,
    total_amount numeric(19,2) NOT NULL,
    user_id bigint NOT NULL,
    last_topup_time timestamp with time zone,
    original_amount numeric(15,2),
    fee_amount numeric(19,2) DEFAULT 0 NOT NULL
);


ALTER TABLE public.budgets OWNER TO postgres;

--
-- Name: budgets_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.budgets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.budgets_id_seq OWNER TO postgres;

--
-- Name: budgets_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.budgets_id_seq OWNED BY public.budgets.id;


--
-- Name: envelopes; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.envelopes (
    id bigint NOT NULL,
    amount numeric(19,2) NOT NULL,
    conditions jsonb,
    created_at timestamp with time zone,
    name character varying(255) NOT NULL,
    remaining_amount numeric(19,2) NOT NULL,
    budget_id bigint NOT NULL,
    last_accessed timestamp with time zone
);


ALTER TABLE public.envelopes OWNER TO postgres;

--
-- Name: envelopes_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.envelopes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.envelopes_id_seq OWNER TO postgres;

--
-- Name: envelopes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.envelopes_id_seq OWNED BY public.envelopes.id;


--
-- Name: leaderboard_entries; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.leaderboard_entries (
    id bigint NOT NULL,
    rank integer,
    score numeric(19,2) NOT NULL,
    updated_at timestamp with time zone,
    leaderboard_id bigint NOT NULL,
    user_id bigint NOT NULL
);


ALTER TABLE public.leaderboard_entries OWNER TO postgres;

--
-- Name: leaderboard_entries_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.leaderboard_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.leaderboard_entries_id_seq OWNER TO postgres;

--
-- Name: leaderboard_entries_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.leaderboard_entries_id_seq OWNED BY public.leaderboard_entries.id;


--
-- Name: leaderboards; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.leaderboards (
    id bigint NOT NULL,
    end_date date NOT NULL,
    metric character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    period character varying(255) NOT NULL,
    start_date date NOT NULL,
    updated_at timestamp with time zone
);


ALTER TABLE public.leaderboards OWNER TO postgres;

--
-- Name: leaderboards_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.leaderboards_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.leaderboards_id_seq OWNER TO postgres;

--
-- Name: leaderboards_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.leaderboards_id_seq OWNED BY public.leaderboards.id;


--
-- Name: otps; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.otps (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    otp_code character varying(6) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL
);


ALTER TABLE public.otps OWNER TO postgres;

--
-- Name: otps_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.otps_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.otps_id_seq OWNER TO postgres;

--
-- Name: otps_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.otps_id_seq OWNED BY public.otps.id;


--
-- Name: revenue_logs; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.revenue_logs (
    id bigint NOT NULL,
    user_id bigint,
    type character varying(50),
    amount numeric(15,2) NOT NULL,
    description character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT revenue_logs_type_check CHECK (((type)::text = ANY (ARRAY[('budget_creation'::character varying)::text, ('movement_fee'::character varying)::text, ('emergency_fee'::character varying)::text, ('envelope_transfer_fee'::character varying)::text])))
);


ALTER TABLE public.revenue_logs OWNER TO postgres;

--
-- Name: revenue_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.revenue_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.revenue_logs_id_seq OWNER TO postgres;

--
-- Name: revenue_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.revenue_logs_id_seq OWNED BY public.revenue_logs.id;


--
-- Name: transaction_logs; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.transaction_logs (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    budget_id bigint,
    source_envelope_id bigint,
    target_envelope_id bigint,
    external_account_id character varying(100),
    amount numeric(15,2) NOT NULL,
    fee numeric(15,2),
    transaction_type character varying(50) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    description character varying(255)
);


ALTER TABLE public.transaction_logs OWNER TO postgres;

--
-- Name: transaction_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.transaction_logs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.transaction_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_badges; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.user_badges (
    id bigint NOT NULL,
    earned_at timestamp with time zone,
    badge_id bigint NOT NULL,
    user_id bigint NOT NULL
);


ALTER TABLE public.user_badges OWNER TO postgres;

--
-- Name: user_badges_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.user_badges_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_badges_id_seq OWNER TO postgres;

--
-- Name: user_badges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.user_badges_id_seq OWNED BY public.user_badges.id;


--
-- Name: user_goals; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.user_goals (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    current_amount numeric(19,2),
    name character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    target_amount numeric(19,2) NOT NULL,
    target_date date,
    envelope_id bigint,
    user_id bigint NOT NULL
);


ALTER TABLE public.user_goals OWNER TO postgres;

--
-- Name: user_goals_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.user_goals_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_goals_id_seq OWNER TO postgres;

--
-- Name: user_goals_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.user_goals_id_seq OWNED BY public.user_goals.id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    bvn character varying(255),
    created_at timestamp with time zone,
    email character varying(255) NOT NULL,
    last_login timestamp with time zone,
    password character varying(255),
    phone character varying(255),
    role character varying(255) NOT NULL,
    profile_data jsonb,
    tnc_accepted boolean DEFAULT false,
    is_verified boolean DEFAULT false NOT NULL,
    budget_preferences jsonb
);


ALTER TABLE public.users OWNER TO postgres;

--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.users_id_seq OWNER TO postgres;

--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: wallets; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.wallets (
    id bigint NOT NULL,
    balance numeric(19,2) NOT NULL,
    currency character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    updated_at timestamp with time zone,
    user_id bigint NOT NULL,
    account_number character varying(255),
    bank_name character varying(255),
    wallet_type character varying(255)
);


ALTER TABLE public.wallets OWNER TO postgres;

--
-- Name: wallets_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.wallets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.wallets_id_seq OWNER TO postgres;

--
-- Name: wallets_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.wallets_id_seq OWNED BY public.wallets.id;


--
-- Name: analytics_logs id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.analytics_logs ALTER COLUMN id SET DEFAULT nextval('public.analytics_logs_id_seq'::regclass);


--
-- Name: badges id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.badges ALTER COLUMN id SET DEFAULT nextval('public.badges_id_seq'::regclass);


--
-- Name: budgets id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.budgets ALTER COLUMN id SET DEFAULT nextval('public.budgets_id_seq'::regclass);


--
-- Name: envelopes id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.envelopes ALTER COLUMN id SET DEFAULT nextval('public.envelopes_id_seq'::regclass);


--
-- Name: leaderboard_entries id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboard_entries ALTER COLUMN id SET DEFAULT nextval('public.leaderboard_entries_id_seq'::regclass);


--
-- Name: leaderboards id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboards ALTER COLUMN id SET DEFAULT nextval('public.leaderboards_id_seq'::regclass);


--
-- Name: otps id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.otps ALTER COLUMN id SET DEFAULT nextval('public.otps_id_seq'::regclass);


--
-- Name: revenue_logs id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.revenue_logs ALTER COLUMN id SET DEFAULT nextval('public.revenue_logs_id_seq'::regclass);


--
-- Name: user_badges id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_badges ALTER COLUMN id SET DEFAULT nextval('public.user_badges_id_seq'::regclass);


--
-- Name: user_goals id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_goals ALTER COLUMN id SET DEFAULT nextval('public.user_goals_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: wallets id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.wallets ALTER COLUMN id SET DEFAULT nextval('public.wallets_id_seq'::regclass);


--
-- Data for Name: analytics_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.analytics_logs (id, created_at, details, event_type, user_id) FROM stdin;
\.


--
-- Data for Name: badges; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.badges (id, active, created_at, description, icon_url, name, threshold) FROM stdin;
\.


--
-- Data for Name: budgets; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.budgets (id, allocated_amount, created_at, duration_days, end_date, name, start_date, status, total_amount, user_id, last_topup_time, original_amount, fee_amount) FROM stdin;
4	0.00	2025-04-07 21:38:49.711545+00	30	2025-05-07	Monthly Expenses	2025-04-07	ACTIVE	10000.00	1	\N	10000.00	0.00
5	0.00	2025-04-07 21:41:45.824337+00	30	2025-05-07	Monthly Expenses	2025-04-07	ACTIVE	50000.00	3	\N	50000.00	0.00
15	15000.00	2025-04-11 14:54:15.25521+00	30	2025-04-30	April 2025	2025-04-01	ACTIVE	15000.00	1	\N	15000.00	0.00
17	15000.00	2025-04-11 15:59:46.016674+00	30	2025-04-30	April 2025	2025-04-01	ACTIVE	15000.00	1	\N	15000.00	0.00
18	15000.00	2025-04-11 16:42:33.492683+00	30	2025-04-30	April 2025	2025-04-01	ACTIVE	15000.00	1	\N	15000.00	0.00
19	15000.00	2025-04-12 01:17:50.163855+00	30	2025-04-30	April 2025	2025-04-01	ACTIVE	15000.00	1	\N	15000.00	0.00
20	15000.00	2025-04-12 01:48:44.28712+00	30	2025-04-30	April 2025	2025-04-01	ACTIVE	15000.00	1	\N	15000.00	0.00
21	15000.00	2025-04-13 04:45:54.025942+00	30	2025-04-14	April Extended	2025-04-01	COMPLETED	15000.00	1	\N	15000.00	0.00
16	25000.00	2025-04-11 15:37:38.449016+00	30	2025-04-14	April Extended	2025-04-01	COMPLETED	25000.00	1	2025-04-14 15:36:16.700523+00	25000.00	0.00
28	100000.00	2025-04-24 15:28:33.601454+00	29	2025-05-19	MAY Budget	2025-04-20	ACTIVE	99900.00	12	\N	99900.00	0.00
29	100000.00	2025-05-01 11:07:34.509647+00	29	2025-05-19	MAY Budget	2025-04-20	ACTIVE	99900.00	12	\N	99900.00	0.00
30	100000.00	2025-05-01 11:15:47.68232+00	29	2025-05-19	MAY Budget	2025-04-20	ACTIVE	99900.00	12	\N	99900.00	0.00
31	100000.00	2025-05-01 11:27:21.702857+00	18	2025-05-19	MAY Budget	2025-05-01	ACTIVE	99900.00	12	\N	99900.00	0.00
32	100000.00	2025-06-01 18:22:23.705124+00	29	2025-05-19	June Budget	2025-04-20	ACTIVE	99900.00	5	\N	99900.00	0.00
33	394921.00	2025-06-03 04:09:15.845036+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	5	\N	499900.00	0.00
34	394921.00	2025-06-03 04:14:30.636694+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	5	\N	499900.00	0.00
35	394921.00	2025-06-03 04:14:36.865133+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	5	\N	499900.00	0.00
36	394921.00	2025-06-06 03:14:28.761291+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
37	394921.00	2025-06-06 03:16:09.261062+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
38	394921.00	2025-06-06 03:16:11.513954+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
39	394921.00	2025-06-06 03:16:13.474039+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
40	394921.00	2025-06-06 03:16:14.822673+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
41	399921.00	2025-06-06 03:17:40.141244+00	29	2025-06-30	June 2025 Budget	2025-06-01	ACTIVE	504900.00	12	2025-06-06 04:15:50.191933+00	500000.00	100.00
42	394921.00	2025-06-06 05:15:55.90911+00	29	2025-06-30	June 2025 Budget 2	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
43	394921.00	2025-06-06 06:15:56.545099+00	29	2025-06-30	June 2025 Budget 3	2025-06-01	ACTIVE	499900.00	12	\N	500000.00	100.00
44	229908.00	2025-06-06 23:38:10.637036+00	30	2025-07-31	July 2025 Budget	2025-07-01	ACTIVE	249900.00	12	\N	250000.00	100.00
\.


--
-- Data for Name: envelopes; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.envelopes (id, amount, conditions, created_at, name, remaining_amount, budget_id, last_accessed) FROM stdin;
1	1999.50	{"type": "daily", "limit": 500}	2025-04-11 14:54:15.256214+00	Lunch	1999.50	15	\N
2	3000.00	{"type": "safe_lock"}	2025-04-11 14:54:15.259719+00	Fun	3000.00	15	\N
3	4999.50	{"type": "strict_lock"}	2025-04-11 14:54:15.259719+00	Savings	4999.50	15	\N
4	3501.00	{"type": "weekly", "limit": 1000}	2025-04-11 14:54:15.259719+00	Transport	3501.00	15	\N
5	1500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-11 14:54:15.259719+00	Offering	1500.00	15	\N
11	1999.50	{"type": "daily", "limit": 500}	2025-04-11 15:59:46.016674+00	Lunch	1999.50	17	\N
12	3000.00	{"type": "safe_lock"}	2025-04-11 15:59:46.01766+00	Fun	3000.00	17	\N
13	4999.50	{"type": "strict_lock"}	2025-04-11 15:59:46.01766+00	Savings	4999.50	17	\N
14	3501.00	{"type": "weekly", "limit": 1000}	2025-04-11 15:59:46.01766+00	Transport	3501.00	17	\N
15	1500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-11 15:59:46.01766+00	Offering	1500.00	17	\N
16	1999.50	{"type": "daily", "limit": 500}	2025-04-11 16:42:33.492683+00	Lunch	1999.50	18	\N
17	3000.00	{"type": "safe_lock"}	2025-04-11 16:42:33.492683+00	Fun	3000.00	18	\N
18	4999.50	{"type": "strict_lock"}	2025-04-11 16:42:33.492683+00	Savings	4999.50	18	\N
19	3501.00	{"type": "weekly", "limit": 1000}	2025-04-11 16:42:33.492683+00	Transport	3501.00	18	\N
20	1500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-11 16:42:33.492683+00	Offering	1500.00	18	\N
21	1999.50	{"type": "daily", "limit": 500}	2025-04-12 01:17:50.163855+00	Lunch	1999.50	19	\N
22	3000.00	{"type": "safe_lock"}	2025-04-12 01:17:50.163855+00	Fun	3000.00	19	\N
23	4999.50	{"type": "strict_lock"}	2025-04-12 01:17:50.163855+00	Savings	4999.50	19	\N
24	3501.00	{"type": "weekly", "limit": 1000}	2025-04-12 01:17:50.163855+00	Transport	3501.00	19	\N
25	1500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-12 01:17:50.163855+00	Offering	1500.00	19	\N
29	3501.00	{"type": "weekly", "limit": 1000}	2025-04-12 01:48:44.28712+00	Transport	3501.00	20	\N
30	1500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-12 01:48:44.28712+00	Offering	1500.00	20	\N
31	1999.50	{"type": "daily", "limit": 500}	2025-04-13 04:45:54.025942+00	Lunch	1999.50	21	\N
32	3000.00	{"type": "safe_lock"}	2025-04-13 04:45:54.025942+00	Fun	3000.00	21	\N
33	4999.50	{"type": "strict_lock"}	2025-04-13 04:45:54.025942+00	Savings	4999.50	21	\N
34	3501.00	{"type": "weekly", "limit": 1000}	2025-04-13 04:45:54.025942+00	Transport	3501.00	21	\N
35	1500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-13 04:45:54.025942+00	Offering	1500.00	21	\N
26	1999.50	{"type": "daily", "limit": 500}	2025-04-12 01:48:44.28712+00	Lunch	1474.50	20	\N
28	4999.50	{"type": "strict_lock"}	2025-04-12 01:48:44.28712+00	Savings	5385.50	20	\N
8	8332.50	{"type": "strict_lock"}	2025-04-11 15:37:38.450021+00	Savings	8332.50	16	\N
10	2500.00	{"day": "Sunday", "type": "dynamic", "end_time": "12:00", "start_time": "08:00"}	2025-04-11 15:37:38.450021+00	Offering	2500.00	16	\N
7	5000.00	{"type": "safe_lock"}	2025-04-11 15:37:38.450021+00	Fun	5392.00	16	\N
6	3332.50	{"type": "daily", "limit": 500}	2025-04-11 15:37:38.450021+00	Lunch	1422.50	16	\N
9	5835.00	{"type": "weekly", "limit": 1000}	2025-04-11 15:37:38.450021+00	Transport	7305.00	16	\N
27	3000.00	{"type": "safe_lock"}	2025-04-12 01:48:44.28712+00	Fun	2490.00	20	\N
44	50000.00	{"type": "daily", "limit": 200.0}	2025-04-24 15:28:33.601454+00	Food	50000.00	28	\N
45	50000.00	{"type": "dynamic", "startDate": "2025-04-20", "intervalDays": 3, "disbursementTime": "09:00"}	2025-04-24 15:28:33.601454+00	Transport	50000.00	28	\N
46	50000.00	{"type": "daily", "limit": 200.0}	2025-05-01 11:07:34.509647+00	Food	50000.00	29	\N
47	50000.00	{"type": "dynamic", "startDate": "2025-04-20", "intervalDays": 3, "disbursementTime": "09:00"}	2025-05-01 11:07:34.509647+00	Transport	50000.00	29	\N
48	50000.00	{"type": "daily", "limit": 200.0}	2025-05-01 11:15:47.68232+00	Food	50000.00	30	\N
49	50000.00	{"type": "dynamic", "startDate": "2025-04-20", "intervalDays": 3, "disbursementTime": "09:00"}	2025-05-01 11:15:47.68232+00	Transport	50000.00	30	\N
50	50000.00	{"type": "daily", "limit": 200.0}	2025-05-01 11:27:21.702857+00	Food	50000.00	31	\N
51	50000.00	{"type": "dynamic", "startDate": "2025-04-20", "intervalDays": 3, "disbursementTime": "09:00"}	2025-05-01 11:27:21.702857+00	Transport	50000.00	31	\N
52	50000.00	{"type": "daily", "limit": 200.0}	2025-06-01 18:22:23.705124+00	Food	50000.00	32	\N
53	50000.00	{"type": "dynamic", "startDate": "2025-04-20", "intervalDays": 3, "disbursementTime": "09:00"}	2025-06-01 18:22:23.705124+00	Transport	50000.00	32	\N
54	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-03 04:09:15.845036+00	Groceries	24995.00	33	\N
55	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-03 04:09:15.845036+00	Transport	19996.00	33	\N
56	29994.00	{"type": "safe_lock"}	2025-06-03 04:09:15.845036+00	Rent	29994.00	33	\N
57	19996.00	{"type": "weekly"}	2025-06-03 04:09:15.845036+00	Utilities	19996.00	33	\N
58	14997.00	{"type": "daily", "limit": 500.0}	2025-06-03 04:09:15.845036+00	Internet	14997.00	33	\N
59	24995.00	{"type": "strict_lock"}	2025-06-03 04:09:15.845036+00	Savings	24995.00	33	\N
60	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-03 04:09:15.845036+00	Entertainment	19996.00	33	\N
61	14997.00	{"type": "weekly"}	2025-06-03 04:09:15.845036+00	Clothing	14997.00	33	\N
62	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-03 04:09:15.845036+00	Health	19996.00	33	\N
63	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-03 04:09:15.845036+00	Education	24995.00	33	\N
64	19996.00	{"type": "safe_lock"}	2025-06-03 04:09:15.845036+00	Insurance	19996.00	33	\N
65	14997.00	{"type": "weekly"}	2025-06-03 04:09:15.845036+00	Dining Out	14997.00	33	\N
66	14997.00	{"type": "daily", "limit": 300.0}	2025-06-03 04:09:15.845036+00	Charity	14997.00	33	\N
67	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-03 04:09:15.845036+00	Travel	19996.00	33	\N
68	14997.00	{"type": "weekly"}	2025-06-03 04:09:15.845036+00	Car Maintenance	14997.00	33	\N
69	19996.00	{"type": "safe_lock"}	2025-06-03 04:09:15.845036+00	Gym Membership	19996.00	33	\N
70	14997.00	{"type": "daily", "limit": 400.0}	2025-06-03 04:09:15.845036+00	Subscriptions	14997.00	33	\N
71	24995.00	{"type": "strict_lock"}	2025-06-03 04:09:15.845036+00	Investments	24995.00	33	\N
72	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-03 04:09:15.845036+00	Hobbies	19996.00	33	\N
73	14997.00	{"type": "emergency", "used": false}	2025-06-03 04:09:15.845036+00	Emergency Fund	14997.00	33	\N
74	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-03 04:14:30.636694+00	Groceries	24995.00	34	\N
75	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-03 04:14:30.636694+00	Transport	19996.00	34	\N
76	29994.00	{"type": "safe_lock"}	2025-06-03 04:14:30.636694+00	Rent	29994.00	34	\N
77	19996.00	{"type": "weekly"}	2025-06-03 04:14:30.636694+00	Utilities	19996.00	34	\N
78	14997.00	{"type": "daily", "limit": 500.0}	2025-06-03 04:14:30.636694+00	Internet	14997.00	34	\N
79	24995.00	{"type": "strict_lock"}	2025-06-03 04:14:30.636694+00	Savings	24995.00	34	\N
80	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-03 04:14:30.636694+00	Entertainment	19996.00	34	\N
81	14997.00	{"type": "weekly"}	2025-06-03 04:14:30.636694+00	Clothing	14997.00	34	\N
82	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-03 04:14:30.636694+00	Health	19996.00	34	\N
83	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-03 04:14:30.636694+00	Education	24995.00	34	\N
84	19996.00	{"type": "safe_lock"}	2025-06-03 04:14:30.636694+00	Insurance	19996.00	34	\N
85	14997.00	{"type": "weekly"}	2025-06-03 04:14:30.636694+00	Dining Out	14997.00	34	\N
86	14997.00	{"type": "daily", "limit": 300.0}	2025-06-03 04:14:30.636694+00	Charity	14997.00	34	\N
87	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-03 04:14:30.636694+00	Travel	19996.00	34	\N
88	14997.00	{"type": "weekly"}	2025-06-03 04:14:30.636694+00	Car Maintenance	14997.00	34	\N
89	19996.00	{"type": "safe_lock"}	2025-06-03 04:14:30.636694+00	Gym Membership	19996.00	34	\N
90	14997.00	{"type": "daily", "limit": 400.0}	2025-06-03 04:14:30.636694+00	Subscriptions	14997.00	34	\N
91	24995.00	{"type": "strict_lock"}	2025-06-03 04:14:30.636694+00	Investments	24995.00	34	\N
92	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-03 04:14:30.636694+00	Hobbies	19996.00	34	\N
93	14997.00	{"type": "emergency", "used": false}	2025-06-03 04:14:30.636694+00	Emergency Fund	14997.00	34	\N
94	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-03 04:14:36.865133+00	Groceries	24995.00	35	\N
95	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-03 04:14:36.865133+00	Transport	19996.00	35	\N
96	29994.00	{"type": "safe_lock"}	2025-06-03 04:14:36.865133+00	Rent	29994.00	35	\N
97	19996.00	{"type": "weekly"}	2025-06-03 04:14:36.865133+00	Utilities	19996.00	35	\N
98	14997.00	{"type": "daily", "limit": 500.0}	2025-06-03 04:14:36.865133+00	Internet	14997.00	35	\N
99	24995.00	{"type": "strict_lock"}	2025-06-03 04:14:36.865133+00	Savings	24995.00	35	\N
100	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-03 04:14:36.865133+00	Entertainment	19996.00	35	\N
101	14997.00	{"type": "weekly"}	2025-06-03 04:14:36.865133+00	Clothing	14997.00	35	\N
102	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-03 04:14:36.865133+00	Health	19996.00	35	\N
103	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-03 04:14:36.865133+00	Education	24995.00	35	\N
104	19996.00	{"type": "safe_lock"}	2025-06-03 04:14:36.865133+00	Insurance	19996.00	35	\N
105	14997.00	{"type": "weekly"}	2025-06-03 04:14:36.865133+00	Dining Out	14997.00	35	\N
106	14997.00	{"type": "daily", "limit": 300.0}	2025-06-03 04:14:36.865133+00	Charity	14997.00	35	\N
107	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-03 04:14:36.865133+00	Travel	19996.00	35	\N
108	14997.00	{"type": "weekly"}	2025-06-03 04:14:36.865133+00	Car Maintenance	14997.00	35	\N
109	19996.00	{"type": "safe_lock"}	2025-06-03 04:14:36.865133+00	Gym Membership	19996.00	35	\N
110	14997.00	{"type": "daily", "limit": 400.0}	2025-06-03 04:14:36.865133+00	Subscriptions	14997.00	35	\N
111	24995.00	{"type": "strict_lock"}	2025-06-03 04:14:36.865133+00	Investments	24995.00	35	\N
112	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-03 04:14:36.865133+00	Hobbies	19996.00	35	\N
113	14997.00	{"type": "emergency", "used": false}	2025-06-03 04:14:36.865133+00	Emergency Fund	14997.00	35	\N
114	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 03:14:28.761291+00	Groceries	24995.00	36	\N
115	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 03:14:28.761291+00	Transport	19996.00	36	\N
116	29994.00	{"type": "safe_lock"}	2025-06-06 03:14:28.761291+00	Rent	29994.00	36	\N
117	19996.00	{"type": "weekly"}	2025-06-06 03:14:28.761291+00	Utilities	19996.00	36	\N
118	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 03:14:28.761291+00	Internet	14997.00	36	\N
119	24995.00	{"type": "strict_lock"}	2025-06-06 03:14:28.761291+00	Savings	24995.00	36	\N
120	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 03:14:28.761291+00	Entertainment	19996.00	36	\N
121	14997.00	{"type": "weekly"}	2025-06-06 03:14:28.761291+00	Clothing	14997.00	36	\N
122	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 03:14:28.761291+00	Health	19996.00	36	\N
123	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 03:14:28.761291+00	Education	24995.00	36	\N
124	19996.00	{"type": "safe_lock"}	2025-06-06 03:14:28.761291+00	Insurance	19996.00	36	\N
125	14997.00	{"type": "weekly"}	2025-06-06 03:14:28.761291+00	Dining Out	14997.00	36	\N
126	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 03:14:28.761291+00	Charity	14997.00	36	\N
127	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 03:14:28.761291+00	Travel	19996.00	36	\N
128	14997.00	{"type": "weekly"}	2025-06-06 03:14:28.761291+00	Car Maintenance	14997.00	36	\N
129	19996.00	{"type": "safe_lock"}	2025-06-06 03:14:28.761291+00	Gym Membership	19996.00	36	\N
130	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 03:14:28.761291+00	Subscriptions	14997.00	36	\N
131	24995.00	{"type": "strict_lock"}	2025-06-06 03:14:28.761291+00	Investments	24995.00	36	\N
132	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 03:14:28.761291+00	Hobbies	19996.00	36	\N
133	14997.00	{"type": "emergency", "used": false}	2025-06-06 03:14:28.761291+00	Emergency Fund	14997.00	36	\N
134	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 03:16:09.261062+00	Groceries	24995.00	37	\N
135	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 03:16:09.261062+00	Transport	19996.00	37	\N
136	29994.00	{"type": "safe_lock"}	2025-06-06 03:16:09.261062+00	Rent	29994.00	37	\N
137	19996.00	{"type": "weekly"}	2025-06-06 03:16:09.261062+00	Utilities	19996.00	37	\N
138	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 03:16:09.261062+00	Internet	14997.00	37	\N
139	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:09.261062+00	Savings	24995.00	37	\N
140	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 03:16:09.261062+00	Entertainment	19996.00	37	\N
141	14997.00	{"type": "weekly"}	2025-06-06 03:16:09.261062+00	Clothing	14997.00	37	\N
142	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 03:16:09.261062+00	Health	19996.00	37	\N
143	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 03:16:09.261062+00	Education	24995.00	37	\N
144	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:09.261062+00	Insurance	19996.00	37	\N
145	14997.00	{"type": "weekly"}	2025-06-06 03:16:09.261062+00	Dining Out	14997.00	37	\N
146	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 03:16:09.261062+00	Charity	14997.00	37	\N
147	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 03:16:09.261062+00	Travel	19996.00	37	\N
148	14997.00	{"type": "weekly"}	2025-06-06 03:16:09.261062+00	Car Maintenance	14997.00	37	\N
149	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:09.261062+00	Gym Membership	19996.00	37	\N
150	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 03:16:09.261062+00	Subscriptions	14997.00	37	\N
151	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:09.261062+00	Investments	24995.00	37	\N
152	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 03:16:09.261062+00	Hobbies	19996.00	37	\N
153	14997.00	{"type": "emergency", "used": false}	2025-06-06 03:16:09.261062+00	Emergency Fund	14997.00	37	\N
154	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 03:16:11.513954+00	Groceries	24995.00	38	\N
155	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 03:16:11.513954+00	Transport	19996.00	38	\N
156	29994.00	{"type": "safe_lock"}	2025-06-06 03:16:11.513954+00	Rent	29994.00	38	\N
157	19996.00	{"type": "weekly"}	2025-06-06 03:16:11.513954+00	Utilities	19996.00	38	\N
158	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 03:16:11.513954+00	Internet	14997.00	38	\N
159	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:11.513954+00	Savings	24995.00	38	\N
160	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 03:16:11.513954+00	Entertainment	19996.00	38	\N
161	14997.00	{"type": "weekly"}	2025-06-06 03:16:11.513954+00	Clothing	14997.00	38	\N
162	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 03:16:11.513954+00	Health	19996.00	38	\N
163	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 03:16:11.513954+00	Education	24995.00	38	\N
164	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:11.513954+00	Insurance	19996.00	38	\N
165	14997.00	{"type": "weekly"}	2025-06-06 03:16:11.513954+00	Dining Out	14997.00	38	\N
166	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 03:16:11.513954+00	Charity	14997.00	38	\N
167	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 03:16:11.513954+00	Travel	19996.00	38	\N
168	14997.00	{"type": "weekly"}	2025-06-06 03:16:11.513954+00	Car Maintenance	14997.00	38	\N
169	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:11.513954+00	Gym Membership	19996.00	38	\N
170	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 03:16:11.513954+00	Subscriptions	14997.00	38	\N
171	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:11.513954+00	Investments	24995.00	38	\N
172	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 03:16:11.513954+00	Hobbies	19996.00	38	\N
173	14997.00	{"type": "emergency", "used": false}	2025-06-06 03:16:11.513954+00	Emergency Fund	14997.00	38	\N
174	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 03:16:13.474039+00	Groceries	24995.00	39	\N
175	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 03:16:13.474039+00	Transport	19996.00	39	\N
176	29994.00	{"type": "safe_lock"}	2025-06-06 03:16:13.474039+00	Rent	29994.00	39	\N
177	19996.00	{"type": "weekly"}	2025-06-06 03:16:13.474039+00	Utilities	19996.00	39	\N
178	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 03:16:13.474039+00	Internet	14997.00	39	\N
179	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:13.474039+00	Savings	24995.00	39	\N
180	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 03:16:13.474039+00	Entertainment	19996.00	39	\N
181	14997.00	{"type": "weekly"}	2025-06-06 03:16:13.474039+00	Clothing	14997.00	39	\N
182	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 03:16:13.474039+00	Health	19996.00	39	\N
183	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 03:16:13.474039+00	Education	24995.00	39	\N
184	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:13.474039+00	Insurance	19996.00	39	\N
185	14997.00	{"type": "weekly"}	2025-06-06 03:16:13.474039+00	Dining Out	14997.00	39	\N
186	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 03:16:13.474039+00	Charity	14997.00	39	\N
187	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 03:16:13.474039+00	Travel	19996.00	39	\N
188	14997.00	{"type": "weekly"}	2025-06-06 03:16:13.474039+00	Car Maintenance	14997.00	39	\N
189	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:13.474039+00	Gym Membership	19996.00	39	\N
190	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 03:16:13.474039+00	Subscriptions	14997.00	39	\N
191	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:13.474039+00	Investments	24995.00	39	\N
192	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 03:16:13.474039+00	Hobbies	19996.00	39	\N
193	14997.00	{"type": "emergency", "used": false}	2025-06-06 03:16:13.474039+00	Emergency Fund	14997.00	39	\N
194	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 03:16:14.822673+00	Groceries	24995.00	40	\N
195	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 03:16:14.822673+00	Transport	19996.00	40	\N
196	29994.00	{"type": "safe_lock"}	2025-06-06 03:16:14.822673+00	Rent	29994.00	40	\N
197	19996.00	{"type": "weekly"}	2025-06-06 03:16:14.822673+00	Utilities	19996.00	40	\N
198	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 03:16:14.822673+00	Internet	14997.00	40	\N
199	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:14.822673+00	Savings	24995.00	40	\N
200	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 03:16:14.822673+00	Entertainment	19996.00	40	\N
201	14997.00	{"type": "weekly"}	2025-06-06 03:16:14.822673+00	Clothing	14997.00	40	\N
202	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 03:16:14.822673+00	Health	19996.00	40	\N
203	24995.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 03:16:14.822673+00	Education	24995.00	40	\N
204	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:14.822673+00	Insurance	19996.00	40	\N
205	14997.00	{"type": "weekly"}	2025-06-06 03:16:14.822673+00	Dining Out	14997.00	40	\N
206	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 03:16:14.822673+00	Charity	14997.00	40	\N
207	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 03:16:14.822673+00	Travel	19996.00	40	\N
208	14997.00	{"type": "weekly"}	2025-06-06 03:16:14.822673+00	Car Maintenance	14997.00	40	\N
209	19996.00	{"type": "safe_lock"}	2025-06-06 03:16:14.822673+00	Gym Membership	19996.00	40	\N
210	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 03:16:14.822673+00	Subscriptions	14997.00	40	\N
211	24995.00	{"type": "strict_lock"}	2025-06-06 03:16:14.822673+00	Investments	24995.00	40	\N
212	19996.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 03:16:14.822673+00	Hobbies	19996.00	40	\N
213	14997.00	{"type": "emergency", "used": false}	2025-06-06 03:16:14.822673+00	Emergency Fund	14997.00	40	\N
220	20249.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 03:17:40.141244+00	Entertainment	20249.00	41	\N
221	15187.00	{"type": "weekly"}	2025-06-06 03:17:40.141244+00	Clothing	15187.00	41	\N
218	15187.00	{"type": "daily", "limit": 500.0}	2025-06-06 03:17:40.141244+00	Internet	14687.00	41	2025-06-06 04:30:07.985053+00
222	20249.00	{"type": "daily", "limit": 1000.0}	2025-06-06 03:17:40.141244+00	Health	20249.00	41	\N
223	25311.50	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 03:17:40.141244+00	Education	25311.50	41	\N
217	20249.00	{"type": "weekly"}	2025-06-06 03:17:40.141244+00	Utilities	20249.00	41	\N
219	25311.50	{"type": "strict_lock"}	2025-06-06 03:17:40.141244+00	Savings	25311.50	41	\N
224	20249.00	{"type": "safe_lock"}	2025-06-06 03:17:40.141244+00	Insurance	20249.00	41	\N
225	15187.00	{"type": "weekly"}	2025-06-06 03:17:40.141244+00	Dining Out	15187.00	41	\N
226	15187.00	{"type": "daily", "limit": 300.0}	2025-06-06 03:17:40.141244+00	Charity	15187.00	41	\N
227	20249.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 03:17:40.141244+00	Travel	20249.00	41	\N
228	15187.00	{"type": "weekly"}	2025-06-06 03:17:40.141244+00	Car Maintenance	15187.00	41	\N
229	20249.00	{"type": "safe_lock"}	2025-06-06 03:17:40.141244+00	Gym Membership	20249.00	41	\N
230	15187.00	{"type": "daily", "limit": 400.0}	2025-06-06 03:17:40.141244+00	Subscriptions	15187.00	41	\N
231	25311.50	{"type": "strict_lock"}	2025-06-06 03:17:40.141244+00	Investments	25311.50	41	\N
232	20249.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 03:17:40.141244+00	Hobbies	20249.00	41	\N
233	15187.00	{"type": "emergency", "used": false}	2025-06-06 03:17:40.141244+00	Emergency Fund	15187.00	41	\N
214	25311.50	{"type": "daily", "limit": 1500.0}	2025-06-06 03:17:40.141244+00	Groceries	23811.50	41	\N
215	20249.00	{"type": "dynamic", "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 03:17:40.141244+00	Transport	21768.50	41	\N
216	30373.50	{"type": "safe_lock"}	2025-06-06 03:17:40.141244+00	Rent	28323.50	41	\N
234	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 05:15:55.90911+00	Groceries	24995.00	42	\N
235	19996.00	{"type": "dynamic", "limit": 12000.0, "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 05:15:55.90911+00	Transport	19996.00	42	\N
236	29994.00	{"type": "safe_lock"}	2025-06-06 05:15:55.90911+00	Rent	29994.00	42	\N
237	19996.00	{"type": "weekly", "limit": 20000.0}	2025-06-06 05:15:55.90911+00	Utilities	19996.00	42	\N
238	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 05:15:55.90911+00	Internet	14997.00	42	\N
239	24995.00	{"type": "strict_lock"}	2025-06-06 05:15:55.90911+00	Savings	24995.00	42	\N
240	19996.00	{"type": "dynamic", "limit": 15000.0, "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 05:15:55.90911+00	Entertainment	19996.00	42	\N
241	14997.00	{"type": "weekly", "limit": 10000.0}	2025-06-06 05:15:55.90911+00	Clothing	14997.00	42	\N
242	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 05:15:55.90911+00	Health	19996.00	42	\N
243	24995.00	{"type": "dynamic", "limit": 12000.0, "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 05:15:55.90911+00	Education	24995.00	42	\N
244	19996.00	{"type": "safe_lock"}	2025-06-06 05:15:55.90911+00	Insurance	19996.00	42	\N
245	14997.00	{"type": "weekly", "limit": 18000.0}	2025-06-06 05:15:55.90911+00	Dining Out	14997.00	42	\N
246	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 05:15:55.90911+00	Charity	14997.00	42	\N
247	19996.00	{"type": "dynamic", "limit": 40000.0, "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 05:15:55.90911+00	Travel	19996.00	42	\N
248	14997.00	{"type": "weekly", "limit": 15000.0}	2025-06-06 05:15:55.90911+00	Car Maintenance	14997.00	42	\N
249	19996.00	{"type": "safe_lock"}	2025-06-06 05:15:55.90911+00	Gym Membership	19996.00	42	\N
250	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 05:15:55.90911+00	Subscriptions	14997.00	42	\N
251	24995.00	{"type": "strict_lock"}	2025-06-06 05:15:55.90911+00	Investments	24995.00	42	\N
252	19996.00	{"type": "dynamic", "limit": 17800.0, "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 05:15:55.90911+00	Hobbies	19996.00	42	\N
253	14997.00	{"type": "emergency", "used": false}	2025-06-06 05:15:55.90911+00	Emergency Fund	14997.00	42	\N
254	24995.00	{"type": "daily", "limit": 1500.0}	2025-06-06 06:15:56.545099+00	Groceries	24995.00	43	\N
256	29994.00	{"type": "safe_lock"}	2025-06-06 06:15:56.545099+00	Rent	29994.00	43	\N
257	19996.00	{"type": "weekly", "limit": 20000.0}	2025-06-06 06:15:56.545099+00	Utilities	19996.00	43	\N
258	14997.00	{"type": "daily", "limit": 500.0}	2025-06-06 06:15:56.545099+00	Internet	14997.00	43	\N
259	24995.00	{"type": "strict_lock"}	2025-06-06 06:15:56.545099+00	Savings	24995.00	43	\N
260	19996.00	{"type": "dynamic", "limit": 15000.0, "startDate": "2025-06-01", "intervalDays": 7, "disbursementTime": "09:00"}	2025-06-06 06:15:56.545099+00	Entertainment	19996.00	43	\N
263	24995.00	{"type": "dynamic", "limit": 12000.0, "startDate": "2025-06-01", "intervalDays": 10, "disbursementTime": "10:00"}	2025-06-06 06:15:56.545099+00	Education	24995.00	43	\N
264	19996.00	{"type": "safe_lock"}	2025-06-06 06:15:56.545099+00	Insurance	19996.00	43	\N
267	19996.00	{"type": "dynamic", "limit": 40000.0, "startDate": "2025-06-01", "intervalDays": 15, "disbursementTime": "11:00"}	2025-06-06 06:15:56.545099+00	Travel	19996.00	43	\N
268	14997.00	{"type": "weekly", "limit": 15000.0}	2025-06-06 06:15:56.545099+00	Car Maintenance	14997.00	43	\N
269	19996.00	{"type": "safe_lock"}	2025-06-06 06:15:56.545099+00	Gym Membership	19996.00	43	\N
270	14997.00	{"type": "daily", "limit": 400.0}	2025-06-06 06:15:56.545099+00	Subscriptions	14997.00	43	\N
271	24995.00	{"type": "strict_lock"}	2025-06-06 06:15:56.545099+00	Investments	24995.00	43	\N
272	19996.00	{"type": "dynamic", "limit": 17800.0, "startDate": "2025-06-01", "intervalDays": 5, "disbursementTime": "12:00"}	2025-06-06 06:15:56.545099+00	Hobbies	19996.00	43	\N
273	14997.00	{"type": "emergency", "used": false}	2025-06-06 06:15:56.545099+00	Emergency Fund	14997.00	43	\N
275	24990.00	{"type": "safe_lock"}	2025-06-06 23:38:10.637036+00	Rent	22990.00	44	\N
255	19996.00	{"type": "dynamic", "limit": 12000.0, "startDate": "2025-06-01", "intervalDays": 3, "disbursementTime": "08:00"}	2025-06-06 06:15:56.545099+00	Transport	20486.00	43	\N
276	12495.00	{"type": "daily", "limit": 1000}	2025-06-06 23:38:10.637036+00	Utilities	11475.00	44	\N
262	19996.00	{"type": "daily", "limit": 1000.0}	2025-06-06 06:15:56.545099+00	Health	18496.00	43	\N
261	14997.00	{"type": "weekly", "limit": 10000.0}	2025-06-06 06:15:56.545099+00	Clothing	15977.00	43	\N
265	14997.00	{"type": "weekly", "limit": 18000.0}	2025-06-06 06:15:56.545099+00	Dining Out	0.00	43	\N
266	14997.00	{"type": "daily", "limit": 300.0}	2025-06-06 06:15:56.545099+00	Charity	29694.06	43	\N
274	12495.00	{"days": ["Monday", "Wednesday", "Friday"], "type": "dynamic", "limit": 3000, "disbursementTime": "08:00"}	2025-06-06 23:38:10.637036+00	Groceries	12495.00	44	\N
283	9996.00	{"days": ["Monday", "Thursday"], "type": "dynamic", "limit": 3000, "disbursementTime": "10:00"}	2025-06-06 23:38:10.637036+00	Education	9996.00	44	\N
285	7497.00	{"type": "safe_lock"}	2025-06-06 23:38:10.637036+00	Subscriptions	7497.00	44	\N
286	7497.00	{"days": ["Wednesday", "Sunday"], "type": "dynamic", "limit": 3000, "disbursementTime": "14:00"}	2025-06-06 23:38:10.637036+00	Gifts	7497.00	44	\N
287	9996.00	{"type": "daily", "limit": 800}	2025-06-06 23:38:10.637036+00	Home Maintenance	9996.00	44	\N
289	7497.00	{"days": ["Tuesday", "Friday"], "type": "dynamic", "limit": 3000, "disbursementTime": "11:00"}	2025-06-06 23:38:10.637036+00	Personal Care	7497.00	44	\N
290	7497.00	{"type": "emergency", "used": false}	2025-06-06 23:38:10.637036+00	Charity	7497.00	44	\N
291	9996.00	{"type": "weekly", "limit": 1000}	2025-06-06 23:38:10.637036+00	Fitness	9996.00	44	\N
292	7497.00	{"days": ["Monday", "Saturday"], "type": "dynamic", "limit": 3000, "disbursementTime": "13:00"}	2025-06-06 23:38:10.637036+00	Miscellaneous	7497.00	44	\N
293	9996.00	{"type": "emergency", "used": false}	2025-06-06 23:38:10.637036+00	Emergency Fund	9996.00	44	\N
281	9996.00	{"type": "daily", "limit": 500}	2025-06-06 23:38:10.637036+00	Clothing	9496.00	44	\N
277	12495.00	{"days": ["Tuesday", "Thursday"], "type": "dynamic", "limit": 3000, "disbursementTime": "09:00"}	2025-06-06 23:38:10.637036+00	Transport	15925.00	44	\N
288	9996.00	{"type": "strict_lock"}	2025-06-06 23:38:10.637036+00	Insurance	996.00	44	\N
280	24990.00	{"type": "strict_lock"}	2025-06-06 23:38:10.637036+00	Savings	22490.00	44	\N
278	9996.00	{"type": "weekly", "limit": 2000}	2025-06-06 23:38:10.637036+00	Entertainment	8996.00	44	\N
284	12495.00	{"type": "weekly", "limit": 1500}	2025-06-06 23:38:10.637036+00	Travel	10995.00	44	\N
279	9996.00	{"days": ["Friday", "Saturday"], "type": "dynamic", "limit": 3000, "disbursementTime": "12:00"}	2025-06-06 23:38:10.637036+00	Dining Out	11451.00	44	\N
282	12495.00	{"type": "emergency", "used": true}	2025-06-06 23:38:10.637036+00	Healthcare	10995.00	44	\N
\.


--
-- Data for Name: leaderboard_entries; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.leaderboard_entries (id, rank, score, updated_at, leaderboard_id, user_id) FROM stdin;
\.


--
-- Data for Name: leaderboards; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.leaderboards (id, end_date, metric, name, period, start_date, updated_at) FROM stdin;
\.


--
-- Data for Name: otps; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.otps (id, user_id, otp_code, created_at, expires_at) FROM stdin;
2	11	673530	2025-04-18 16:40:14.663487+00	2025-04-18 16:45:14.663487+00
10	15	155728	2025-06-11 00:02:46.154054+00	2025-06-11 00:07:46.154054+00
11	16	024818	2025-06-12 10:48:47.234121+00	2025-06-12 10:53:47.234121+00
12	17	506972	2025-06-12 10:49:28.379528+00	2025-06-12 10:54:28.379528+00
13	18	710078	2025-06-13 09:17:56.286167+00	2025-06-13 09:22:56.286167+00
14	19	083736	2025-06-13 09:24:31.703486+00	2025-06-13 09:29:31.703486+00
\.


--
-- Data for Name: revenue_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.revenue_logs (id, user_id, type, amount, description, created_at) FROM stdin;
1	1	budget_creation	100.00	Created budget: April 2025	2025-04-11 15:54:15.314944+00
2	1	budget_creation	100.00	Created budget: April 2025	2025-04-11 16:37:38.511945+00
3	1	budget_creation	100.00	Created budget: April 2025	2025-04-11 16:59:46.08098+00
4	1	budget_creation	100.00	Created budget: April 2025	2025-04-11 17:42:33.559838+00
5	1	budget_creation	100.00	Created budget: April 2025	2025-04-12 02:17:50.207346+00
6	1	budget_creation	100.00	Created budget: April 2025	2025-04-12 02:48:44.323241+00
7	1	envelope_transfer_fee	8.00	Transfer from envelope 6 to 7 (fee: 2%)	2025-04-12 06:48:22.283212+00
8	1	envelope_transfer_fee	10.00	Transfer from envelope 6 to 6 (fee: 2%)	2025-04-12 06:59:30.977825+00
9	1	envelope_transfer_fee	5.00	Transfer from envelope 6 to 9 (fee: 2%)	2025-04-12 07:03:52.553562+00
10	1	envelope_transfer_fee	5.00	Transfer from envelope 6 to 9 (fee: 2%)	2025-04-12 07:03:57.344943+00
11	1	envelope_transfer_fee	5.00	Transfer from envelope 6 to 9 (fee: 2%)	2025-04-12 07:04:01.070914+00
12	1	envelope_transfer_fee	5.00	Transfer from envelope 6 to 9 (fee: 2%)	2025-04-12 07:04:22.117176+00
13	1	envelope_transfer_fee	5.00	Transfer from envelope 6 to 9 (fee: 2%)	2025-04-12 07:04:26.107272+00
14	1	envelope_transfer_fee	5.00	Transfer from envelope 6 to 9 (fee: 2%)	2025-04-12 07:04:27.865798+00
15	1	envelope_transfer_fee	4.00	Transfer in Budget 20 from Fun to Savings (fee: 1%)	2025-04-12 17:12:05.493329+00
16	1	envelope_transfer_fee	8.00	Transfer in Budget 20 from Lunch to Fun (fee: 2%)	2025-04-12 17:13:32.266894+00
17	1	envelope_transfer_fee	1.60	Transfer in Budget 20 from Lunch to Fun (fee: 2%)	2025-04-12 17:14:02.830738+00
18	1	envelope_transfer_fee	0.40	Transfer in Budget 20 from Lunch to Fun (fee: 2%)	2025-04-12 17:14:27.241796+00
19	1	envelope_transfer_fee	25.00	Transfer in Budget 20 from Savings to Lunch (fee: 5%)	2025-04-12 17:17:11.186419+00
20	1	envelope_transfer_fee	2.00	Transfer in Budget 20 from Fun to external account 1234567890@bank (fee: 1%)	2025-04-12 17:21:35.512197+00
21	1	budget_creation	100.00	Created budget: MAY 2025	2025-04-13 05:45:54.046474+00
22	1	envelope_transfer_fee	10.00	Transfer in Budget 20 from Lunch to Savings (fee: 2%)	2025-04-14 16:35:54.962992+00
23	1	envelope_transfer_fee	2.00	Transfer in Budget 20 from Fun to external account 1234567890@bank (fee: 1%)	2025-04-14 16:36:05.779995+00
24	1	envelope_transfer_fee	2.00	Transfer in Budget 20 from Fun to external account 1234567890@bank (fee: 1%)	2025-04-14 16:50:51.511956+00
26	12	budget_creation	100.00	Budget fee for 29 days	2025-04-24 16:28:33.661339+00
27	12	budget_creation	100.00	Budget fee for 29 days	2025-05-01 12:07:34.714406+00
28	12	budget_creation	100.00	Budget fee for 29 days	2025-05-01 12:15:47.726618+00
29	12	budget_creation	100.00	Budget fee for 18 days	2025-05-01 12:27:21.865705+00
30	5	budget_creation	100.00	Budget fee for 29 days	2025-06-01 19:22:23.809475+00
31	5	budget_creation	100.00	Budget fee for 29 days	2025-06-03 05:09:16.065734+00
32	5	budget_creation	100.00	Budget fee for 29 days	2025-06-03 05:14:30.719466+00
33	5	budget_creation	100.00	Budget fee for 29 days	2025-06-03 05:14:36.930421+00
34	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 04:14:29.027884+00
35	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 04:16:09.359617+00
36	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 04:16:11.620907+00
37	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 04:16:13.559627+00
38	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 04:16:14.904809+00
39	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 04:17:40.230165+00
40	12	envelope_transfer_fee	20.00	Transfer in Budget 41 from Groceries to Transport (fee: 2%)	2025-06-06 04:46:22.794315+00
41	12	envelope_transfer_fee	10.00	Transfer in Budget 41 from Groceries to Transport (fee: 2%)	2025-06-06 04:48:04.32453+00
42	12	envelope_transfer_fee	0.50	Transfer in Budget 41 from Rent to Transport (fee: 1%)	2025-06-06 04:56:30.643545+00
43	12	envelope_transfer_fee	20.00	Transfer in Budget 41 from Rent to external account monthlyIncome@bank (fee: 1%)	2025-06-06 05:09:41.582762+00
44	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 06:15:56.028122+00
45	1	budget_creation	100.00	Budget fee for 29 days	2025-06-06 07:15:56.70352+00
46	12	envelope_transfer_fee	10.00	Transfer in Budget 43 from Health to Transport (fee: 2%)	2025-06-06 21:30:31.27563+00
47	12	envelope_transfer_fee	10.00	Transfer in Budget 43 from Health to Clothing (fee: 2%)	2025-06-06 22:41:37.288173+00
48	12	envelope_transfer_fee	10.00	Transfer in Budget 43 from Health to Clothing (fee: 2%)	2025-06-07 00:53:41.944534+00
49	12	envelope_transfer_fee	10.00	Transfer in Budget 43 from Dining Out to Charity (fee: 2%)	2025-06-06 22:59:49.848758+00
50	12	envelope_transfer_fee	280.00	Transfer in Budget 43 from Dining Out to Charity (fee: 2%)	2025-06-06 23:03:02.874042+00
51	12	envelope_transfer_fee	9.94	Transfer in Budget 43 from Dining Out to Charity (fee: 2%)	2025-06-06 23:03:31.713392+00
52	1	budget_creation	100.00	Budget fee for 30 days	2025-06-07 00:38:10.637036+00
53	12	envelope_transfer_fee	20.00	Transfer in Budget 44 from Rent to Utilities (fee: 1%)	2025-06-07 13:04:35.689539+00
54	12	envelope_transfer_fee	20.00	Transfer in Budget 44 from Utilities to Transport (fee: 2%)	2025-06-07 13:05:43.402785+00
55	12	envelope_transfer_fee	20.00	Transfer in Budget 44 from Utilities to Transport (fee: 2%)	2025-06-08 13:06:45.099586+00
56	12	envelope_transfer_fee	20.00	Transfer in Budget 44 from Utilities to Transport (fee: 2%)	2025-06-09 13:23:31.782756+00
57	12	envelope_transfer_fee	10.00	Transfer in Budget 44 from Clothing to Transport (fee: 2%)	2025-06-08 09:45:00.802687+00
58	12	envelope_transfer_fee	10.00	Transfer in Budget 44 from Entertainment to Dining Out (fee: 2%)	2025-06-08 09:47:43.593767+00
59	12	envelope_transfer_fee	10.00	Transfer in Budget 44 from Entertainment to Dining Out (fee: 2%)	2025-06-08 09:47:51.684261+00
60	12	envelope_transfer_fee	100.00	Transfer in Budget 44 from Savings to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:20:02.21379+00
61	12	envelope_transfer_fee	30.00	Transfer in Budget 44 from Travel to external account Access Bank/0123456789 (John Doe) (fee: 2%)	2025-06-08 10:26:37.654561+00
62	12	envelope_transfer_fee	75.00	Transfer in Budget 44 from Insurance to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:27:59.773885+00
63	12	envelope_transfer_fee	75.00	Transfer in Budget 44 from Insurance to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:28:03.01656+00
64	12	envelope_transfer_fee	75.00	Transfer in Budget 44 from Insurance to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:28:05.317063+00
65	12	envelope_transfer_fee	75.00	Transfer in Budget 44 from Insurance to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:28:07.129677+00
66	12	envelope_transfer_fee	75.00	Transfer in Budget 44 from Insurance to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:28:08.719057+00
67	12	envelope_transfer_fee	75.00	Transfer in Budget 44 from Insurance to external account Access Bank/0123456789 (John Doe) (fee: 5%)	2025-06-08 10:28:10.236328+00
68	12	envelope_transfer_fee	25.00	Transfer in Budget 44 from Savings to Dining Out (fee: 5%)	2025-06-08 11:15:34.079137+00
69	12	envelope_transfer_fee	150.00	Transfer in Budget 44 from Healthcare to external account Access Bank/0123456789 (John Doe) (fee: 10%)	2025-06-08 12:49:31.396432+00
\.


--
-- Data for Name: transaction_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.transaction_logs (id, user_id, budget_id, source_envelope_id, target_envelope_id, external_account_id, amount, fee, transaction_type, created_at, description) FROM stdin;
1	1	20	27	28	\N	400.00	4.00	envelope_to_envelope	2025-04-12 17:12:05.403778+00	\N
2	1	20	26	27	\N	400.00	8.00	envelope_to_envelope	2025-04-12 17:13:32.234531+00	\N
3	1	20	26	27	\N	80.00	1.60	envelope_to_envelope	2025-04-12 17:14:02.830738+00	\N
4	1	20	26	27	\N	20.00	0.40	envelope_to_envelope	2025-04-12 17:14:27.241796+00	\N
5	1	20	28	26	\N	500.00	25.00	envelope_to_envelope	2025-04-12 17:17:11.186419+00	\N
6	1	20	27	\N	1234567890@bank	200.00	2.00	envelope_to_external	2025-04-12 17:21:35.505173+00	\N
7	1	16	\N	\N	\N	5000.00	0.00	wallet_to_budget	2025-04-12 17:54:35.1032+00	\N
8	1	16	\N	\N	\N	0.00	0.00	budget_extension	2025-04-13 05:22:30.386863+00	\N
9	1	16	\N	\N	\N	0.00	0.00	budget_extension	2025-04-13 05:43:24.64587+00	\N
10	1	16	\N	\N	\N	0.00	0.00	budget_extension	2025-04-13 05:43:44.294372+00	\N
11	1	16	\N	\N	\N	0.00	0.00	budget_extension	2025-04-13 05:44:39.59352+00	\N
12	1	21	\N	\N	\N	0.00	0.00	budget_extension	2025-04-13 05:46:54.26991+00	\N
13	1	20	26	28	\N	500.00	10.00	envelope_to_envelope	2025-04-14 16:35:54.942031+00	\N
14	1	20	27	\N	1234567890@bank	200.00	2.00	envelope_to_external	2025-04-14 16:36:05.777915+00	\N
15	1	16	\N	\N	\N	5000.00	0.00	wallet_to_budget	2025-04-14 16:36:16.711136+00	\N
16	1	21	\N	\N	\N	0.00	0.00	budget_extension	2025-04-14 16:36:27.371591+00	\N
17	1	20	27	\N	1234567890@bank	200.00	2.00	envelope_to_external	2025-04-14 16:50:51.509955+00	\N
18	1	21	\N	\N	\N	0.00	0.00	budget_extension	2025-04-14 16:51:16.098198+00	\N
19	12	\N	\N	\N	\N	200000.00	0.00	wallet_deposit	2025-04-24 10:38:38.151626+00	\N
29	12	\N	\N	\N	\N	100000.00	0.00	wallet_deduction	2025-04-24 16:28:33.645175+00	\N
30	12	28	\N	\N	\N	100000.00	\N	budget_allocation	2025-04-24 16:28:33.653159+00	\N
31	12	28	\N	\N	\N	100.00	\N	budget_creation_fee	2025-04-24 16:28:33.656341+00	\N
32	12	\N	\N	\N	\N	100000.00	0.00	wallet_deduction	2025-05-01 12:07:34.65541+00	\N
33	12	29	\N	\N	\N	100000.00	\N	budget_allocation	2025-05-01 12:07:34.697922+00	\N
34	12	29	\N	\N	\N	100.00	\N	budget_creation_fee	2025-05-01 12:07:34.702108+00	\N
35	12	\N	\N	\N	\N	2000000.00	0.00	wallet_deposit	2025-05-01 12:12:28.117803+00	\N
36	12	\N	\N	\N	\N	100000.00	0.00	wallet_deduction	2025-05-01 12:15:47.707043+00	\N
37	12	30	\N	\N	\N	100000.00	\N	budget_allocation	2025-05-01 12:15:47.715064+00	\N
38	12	30	\N	\N	\N	100.00	\N	budget_creation_fee	2025-05-01 12:15:47.7231+00	\N
39	12	\N	\N	\N	\N	100000.00	0.00	wallet_deduction	2025-05-01 12:27:21.832345+00	\N
40	12	31	\N	\N	\N	100000.00	\N	budget_allocation	2025-05-01 12:27:21.848875+00	\N
41	12	31	\N	\N	\N	100.00	\N	budget_creation_fee	2025-05-01 12:27:21.856897+00	\N
42	5	\N	\N	\N	\N	1000000.00	0.00	wallet_deposit	2025-06-01 19:21:25.508758+00	\N
43	5	\N	\N	\N	\N	100000.00	0.00	wallet_deduction	2025-06-01 19:22:23.789477+00	\N
44	5	32	\N	\N	\N	100000.00	\N	budget_allocation	2025-06-01 19:22:23.7945+00	\N
45	5	32	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-01 19:22:23.799501+00	\N
46	5	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-03 05:09:15.857073+00	\N
47	5	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-03 05:09:15.878836+00	\N
48	5	\N	\N	\N	\N	395021.00	0.00	wallet_deduction	2025-06-03 05:09:16.022496+00	\N
49	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-03 05:09:16.052003+00	\N
50	5	33	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-03 05:09:16.055006+00	\N
51	5	33	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-03 05:09:16.059999+00	\N
52	5	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-03 05:14:30.640952+00	\N
53	5	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-03 05:14:30.64217+00	\N
54	5	\N	\N	\N	\N	395021.00	0.00	wallet_deduction	2025-06-03 05:14:30.698462+00	\N
55	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-03 05:14:30.710466+00	\N
56	5	34	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-03 05:14:30.715466+00	\N
57	5	34	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-03 05:14:30.71749+00	\N
58	5	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-03 05:14:36.869458+00	\N
59	5	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-03 05:14:36.870453+00	\N
60	5	\N	\N	\N	\N	395021.00	0.00	wallet_deduction	2025-06-03 05:14:36.914407+00	\N
61	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-03 05:14:36.924409+00	\N
62	5	35	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-03 05:14:36.926397+00	\N
63	5	35	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-03 05:14:36.92939+00	\N
64	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 04:14:28.77635+00	\N
65	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 04:14:28.863934+00	\N
66	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 04:14:28.8775+00	\N
67	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 04:14:28.881501+00	\N
68	12	36	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 04:14:29.021342+00	\N
69	12	36	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 04:14:29.024889+00	\N
70	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 04:16:09.26358+00	\N
71	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 04:16:09.280112+00	\N
72	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 04:16:09.291787+00	\N
73	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 04:16:09.295864+00	\N
74	12	37	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 04:16:09.355611+00	\N
75	12	37	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 04:16:09.358674+00	\N
76	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 04:16:11.518954+00	\N
77	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 04:16:11.531145+00	\N
78	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 04:16:11.547011+00	\N
79	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 04:16:11.549011+00	\N
80	12	38	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 04:16:11.613899+00	\N
81	12	38	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 04:16:11.618907+00	\N
82	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 04:16:13.476102+00	\N
83	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 04:16:13.490025+00	\N
84	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 04:16:13.498694+00	\N
85	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 04:16:13.50132+00	\N
86	12	39	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 04:16:13.555587+00	\N
87	12	39	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 04:16:13.557588+00	\N
88	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 04:16:14.824685+00	\N
89	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 04:16:14.838235+00	\N
90	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 04:16:14.84676+00	\N
91	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 04:16:14.849818+00	\N
92	12	40	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 04:16:14.898205+00	\N
93	12	40	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 04:16:14.902753+00	\N
94	12	\N	\N	\N	\N	50000.00	0.00	wallet_deposit	2025-06-06 04:17:32.447787+00	\N
95	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 04:17:40.145827+00	\N
96	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 04:17:40.157255+00	\N
97	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 04:17:40.166716+00	\N
98	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 04:17:40.167713+00	\N
99	12	41	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 04:17:40.226163+00	\N
100	12	41	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 04:17:40.227165+00	\N
101	12	41	214	215	\N	1000.00	20.00	envelope_to_envelope	2025-06-06 04:46:22.768882+00	\N
102	12	41	214	215	\N	500.00	10.00	envelope_to_envelope	2025-06-06 04:48:04.32122+00	\N
103	12	41	216	215	\N	50.00	0.50	envelope_to_envelope	2025-06-06 04:56:30.629663+00	\N
104	12	41	216	\N	monthlyIncome@bank	2000.00	20.00	envelope_to_external	2025-06-06 05:09:41.574198+00	\N
105	12	41	\N	\N	\N	5000.00	0.00	wallet_to_budget	2025-06-06 05:15:50.239682+00	\N
106	12	41	218	\N	\N	500.00	0.00	envelope_spend	2025-06-06 05:30:07.985053+00	\N
107	12	\N	\N	\N	\N	5000000.00	0.00	wallet_deposit	2025-06-06 06:10:51.722388+00	\N
108	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 06:15:55.913107+00	\N
109	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 06:15:55.932723+00	\N
110	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 06:15:55.944245+00	\N
111	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 06:15:55.94676+00	\N
112	12	42	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 06:15:56.019148+00	\N
113	12	42	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 06:15:56.023599+00	\N
114	12	\N	\N	\N	\N	394921.00	0.00	wallet_deduction	2025-06-06 07:15:56.553626+00	\N
115	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-06 07:15:56.600654+00	\N
116	12	\N	\N	\N	\N	104979.00	0.00	wallet_deposit	2025-06-06 07:15:56.613549+00	\N
117	12	\N	\N	\N	\N	104979.00	\N	budget_unallocated_refunded	2025-06-06 07:15:56.617141+00	\N
118	12	43	\N	\N	\N	394921.00	\N	budget_allocation	2025-06-06 07:15:56.699355+00	\N
119	12	43	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-06 07:15:56.701521+00	\N
120	12	43	262	255	\N	500.00	10.00	envelope_to_envelope	2025-06-06 21:30:31.228514+00	\N
121	12	43	262	261	\N	500.00	10.00	envelope_to_envelope	2025-06-06 22:41:37.261816+00	\N
122	12	43	262	261	\N	500.00	10.00	envelope_to_envelope	2025-06-07 00:53:41.9406+00	\N
123	12	43	265	266	\N	500.00	10.00	envelope_to_envelope	2025-06-06 22:59:49.824354+00	\N
124	12	43	265	266	\N	14000.00	280.00	envelope_to_envelope	2025-06-06 23:03:02.870041+00	\N
125	12	43	265	266	\N	497.00	9.94	envelope_to_envelope	2025-06-06 23:03:31.70827+00	\N
126	12	\N	\N	\N	\N	229908.00	0.00	wallet_deduction	2025-06-07 00:38:10.673444+00	\N
127	1	\N	\N	\N	\N	100.00	0.00	wallet_deposit	2025-06-07 00:38:10.724095+00	\N
128	12	\N	\N	\N	\N	19992.00	0.00	wallet_deposit	2025-06-07 00:38:10.740244+00	\N
129	12	\N	\N	\N	\N	19992.00	\N	budget_unallocated_refunded	2025-06-07 00:38:10.637036+00	\N
130	12	44	\N	\N	\N	229908.00	\N	budget_allocation	2025-06-07 00:38:10.637036+00	\N
131	12	44	\N	\N	\N	100.00	\N	budget_creation_fee	2025-06-07 00:38:10.637036+00	\N
132	12	44	275	276	\N	2000.00	20.00	envelope_to_envelope	2025-06-07 13:04:35.689539+00	\N
133	12	44	276	277	\N	1000.00	20.00	envelope_to_envelope	2025-06-07 13:05:43.402785+00	\N
134	12	44	276	277	\N	1000.00	20.00	envelope_to_envelope	2025-06-08 13:06:45.099586+00	\N
135	12	44	276	277	\N	1000.00	20.00	envelope_to_envelope	2025-06-09 13:23:31.782756+00	\N
136	12	44	281	277	\N	500.00	10.00	envelope_to_envelope	2025-06-08 09:45:00.802687+00	\N
137	12	44	278	279	\N	500.00	10.00	envelope_to_envelope	2025-06-08 09:47:43.593767+00	\N
138	12	44	278	279	\N	500.00	10.00	envelope_to_envelope	2025-06-08 09:47:51.684261+00	\N
142	12	44	280	\N	0123456789	2000.00	100.00	envelope_to_external	2025-06-08 10:20:02.21379+00	\N
143	12	44	284	\N	0123456789	1500.00	30.00	envelope_to_external	2025-06-08 10:26:37.654561+00	\N
144	12	44	288	\N	0123456789	1500.00	75.00	envelope_to_external	2025-06-08 10:27:59.773885+00	\N
145	12	44	288	\N	0123456789	1500.00	75.00	envelope_to_external	2025-06-08 10:28:03.01656+00	\N
146	12	44	288	\N	0123456789	1500.00	75.00	envelope_to_external	2025-06-08 10:28:05.317063+00	\N
147	12	44	288	\N	0123456789	1500.00	75.00	envelope_to_external	2025-06-08 10:28:07.129677+00	\N
148	12	44	288	\N	0123456789	1500.00	75.00	envelope_to_external	2025-06-08 10:28:08.719057+00	\N
149	12	44	288	\N	0123456789	1500.00	75.00	envelope_to_external	2025-06-08 10:28:10.236328+00	\N
152	12	44	280	279	\N	500.00	25.00	envelope_to_envelope	2025-06-08 11:15:34.079137+00	\N
154	12	44	282	\N	0123456789	1500.00	150.00	envelope_to_external	2025-06-08 12:49:31.396432+00	\N
\.


--
-- Data for Name: user_badges; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.user_badges (id, earned_at, badge_id, user_id) FROM stdin;
\.


--
-- Data for Name: user_goals; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.user_goals (id, created_at, current_amount, name, status, target_amount, target_date, envelope_id, user_id) FROM stdin;
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.users (id, bvn, created_at, email, last_login, password, phone, role, profile_data, tnc_accepted, is_verified, budget_preferences) FROM stdin;
2	\N	2025-04-06 22:17:51.202171+00	olaore4040@gmail.com	\N	\N	\N	USER	\N	f	f	\N
3	\N	2025-04-07 21:04:37.395967+00	osazuwaidubor01@gmail.com	\N	$2a$10$pi.AKuw63dq5GYgyLPNP2u0s1QMIJVOp7vCU7cp7dnZWWEzf4lFDm	07081783593	USER	\N	f	f	\N
4	\N	2025-04-13 15:44:10.38994+00	olaore40406@gmail.com	\N	$2a$10$.4ZpDBOSjCko5W2omcsT2.qtbgpF0ZcbBeTX8w5M5IHnnj45Xh2Bq	08060214033	USER	\N	f	f	\N
1	\N	2025-04-06 22:04:55.961508+00	olaore66@gmail.com	\N	$2a$10$NdgDiULGtHYtvzrH3/DzruzCU6bPjov5kHXcbJ6QCfHCpDdD4yC/C	08060214037	USER	{"occupation": "Software developer", "mainExpense": "Food", "savingsGoal": "Save 10000", "monthlyIncome": 50000}	f	f	\N
6	\N	2025-04-16 20:42:53.944+00	osazuwaidubor0@gmail.com	\N	$2a$10$FqlX9TyBI.YVy1dPK2vowOjmpFmoZyLZt7ewvAidW36B0FcOExqtK	09088869876	USER	\N	f	f	\N
7	\N	2025-04-17 03:37:19.258+00	abraham@gmail.com	\N	$2a$10$e/8fe3mljNXKa.b8f.rwiuqraFZ8b58U0E.hOkeUgUYCVXj2r7Cxq	08129489558	ADMIN	\N	f	f	\N
11	\N	2025-04-18 16:40:14.514+00	test@moniewise.com	2025-04-19 16:26:15.989789+00	$2a$10$aUJFgME.v6RtunOtU1T.Tuw3LdQZGgyyrGzHUTGqNn2HH48xDbinG	+2341234567890	ADMIN	{"dob": "Software Developer", "occupation": "Software Developer", "mainExpense": "Self growth", "savingsGoal": "Save live abroad", "monthlyIncome": 5000000}	f	f	\N
5	\N	2025-04-16 06:31:16.128+00	abrahamiborida@gmail.com	2025-06-06 02:28:52.272613+00	$2a$10$kKmYAHHhIGEoti47XFIl9eTA.p9JeN46ItH36BTbmif72LQSHJ8FW	08129489559	ADMIN	{"dob": "Software Engineer", "occupation": "Software Engineer", "mainExpense": "Self growth", "savingsGoal": "Save live abroad", "monthlyIncome": 2000000}	f	t	\N
12	\N	2025-04-19 17:05:38.766+00	test2@moniewise.com	2025-06-08 12:49:21.576902+00	$2a$10$naBc9kzOlGX6/A2CS1DCiudLcEW.emfAHNkOvoFoncrAn13twQgXK	+23458473638839	ADMIN	{"dob": [1990, 1, 1], "name": "Abraham Iborida", "occupation": "Software Engineer", "mainExpense": "Self growth", "savingsGoal": "Save live abroad", "monthlyIncome": 2000000, "acceptedTncVersion": "1.0"}	t	t	\N
15	\N	2025-06-11 00:02:46.074+00	fluttermoniewise@test.com	\N	$2a$10$zCTaB.AupNssQii5VlOgBuXY6xDHWIrvyQ637LQJRJQqfUV3RWMeW	09088820193	USER	{}	f	f	\N
16	\N	2025-06-12 10:48:47.151+00	testui@moniewise.com	\N	$2a$10$2UcGLG5aRuexf9w9ui216OsjTGV4Io1rqjMsqhDQsNMCMkgWtaq12	09012345678	USER	{}	f	f	\N
17	\N	2025-06-12 10:49:28.368+00	testui2@moniewise.com	\N	$2a$10$rPOVm6FQnLsj7Etgbwg6hOdCq5MHo2Gt2Z4BztamgJY2wHnOQh55W	09012345679	USER	{}	f	f	\N
18	\N	2025-06-13 09:17:56.133+00	tester03@gmail.com	\N	$2a$10$fPODcnc1ZRDDWBRBwzzDTuPvpzAoSuF0IwpbpHw9ASYkYx45rDUqi	08129489551	ADMIN	{}	f	f	\N
19	\N	2025-06-13 09:24:31.689+00	testui4@moniewise.com	\N	$2a$10$MsT1/mQb3eVtH2EO2B2QbubOQfBzgnmxlwJ/BiU0.sBbMfAJG4QS2	09012234434	USER	{}	f	f	\N
\.


--
-- Data for Name: wallets; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.wallets (id, balance, currency, status, updated_at, user_id, account_number, bank_name, wallet_type) FROM stdin;
1	0.00	NGN	ACTIVE	2025-04-16 04:35:55.885353+00	2	\N	\N	\N
2	0.00	NGN	ACTIVE	2025-04-16 04:35:55.923966+00	3	\N	\N	\N
3	0.00	NGN	ACTIVE	2025-04-16 04:35:55.928479+00	4	\N	\N	\N
9	0.00	NGN	ACTIVE	2025-04-16 20:42:54.083+00	6	\N	\N	\N
10	0.00	NGN	ACTIVE	2025-04-17 03:37:20.223+00	7	\N	\N	\N
12	0.00	NGN	ACTIVE	2025-04-18 16:40:14.633+00	11	TEST-11-88992	MonieWise Test Bank	\N
8	29874.00	NGN	ACTIVE	2025-04-16 06:31:16.166+00	5	\N	\N	\N
4	1200.00	NGN	ACTIVE	2025-04-16 04:35:55.932+00	1	\N	\N	\N
13	4320548.00	NGN	ACTIVE	2025-04-19 17:05:38.884+00	12	TEST-12-63556	Stub Access Bank	\N
14	0.00	NGN	ACTIVE	2025-06-11 00:02:46.13+00	15	TEST-15-94026	MonieWise Test Bank	\N
15	0.00	NGN	ACTIVE	2025-06-12 10:48:47.214+00	16	TEST-16-21167	Stub Access Bank	\N
16	0.00	NGN	ACTIVE	2025-06-12 10:49:28.374+00	17	TEST-17-68520	Virtual Titan Bank	\N
17	0.00	NGN	ACTIVE	2025-06-13 09:17:56.249+00	18	TEST-18-63644	Virtual Titan Bank	\N
18	0.00	NGN	ACTIVE	2025-06-13 09:24:31.695+00	19	TEST-19-98522	MonieWise Test Bank	\N
\.


--
-- Name: analytics_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.analytics_logs_id_seq', 1, false);


--
-- Name: badges_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.badges_id_seq', 1, false);


--
-- Name: budgets_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.budgets_id_seq', 44, true);


--
-- Name: envelopes_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.envelopes_id_seq', 293, true);


--
-- Name: leaderboard_entries_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.leaderboard_entries_id_seq', 1, false);


--
-- Name: leaderboards_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.leaderboards_id_seq', 1, false);


--
-- Name: otps_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.otps_id_seq', 14, true);


--
-- Name: revenue_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.revenue_logs_id_seq', 69, true);


--
-- Name: transaction_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.transaction_logs_id_seq', 156, true);


--
-- Name: user_badges_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.user_badges_id_seq', 1, false);


--
-- Name: user_goals_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.user_goals_id_seq', 1, false);


--
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.users_id_seq', 19, true);


--
-- Name: wallets_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.wallets_id_seq', 18, true);


--
-- Name: analytics_logs analytics_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.analytics_logs
    ADD CONSTRAINT analytics_logs_pkey PRIMARY KEY (id);


--
-- Name: badges badges_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.badges
    ADD CONSTRAINT badges_pkey PRIMARY KEY (id);


--
-- Name: budgets budgets_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.budgets
    ADD CONSTRAINT budgets_pkey PRIMARY KEY (id);


--
-- Name: envelopes envelopes_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.envelopes
    ADD CONSTRAINT envelopes_pkey PRIMARY KEY (id);


--
-- Name: leaderboard_entries leaderboard_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboard_entries
    ADD CONSTRAINT leaderboard_entries_pkey PRIMARY KEY (id);


--
-- Name: leaderboards leaderboards_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboards
    ADD CONSTRAINT leaderboards_pkey PRIMARY KEY (id);


--
-- Name: otps otps_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.otps
    ADD CONSTRAINT otps_pkey PRIMARY KEY (id);


--
-- Name: revenue_logs revenue_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.revenue_logs
    ADD CONSTRAINT revenue_logs_pkey PRIMARY KEY (id);


--
-- Name: transaction_logs transaction_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transaction_logs
    ADD CONSTRAINT transaction_logs_pkey PRIMARY KEY (id);


--
-- Name: user_badges uk5r2v5xn0il3p8dc9nf4v94r2b; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_badges
    ADD CONSTRAINT uk5r2v5xn0il3p8dc9nf4v94r2b UNIQUE (user_id, badge_id);


--
-- Name: users uk_6dotkott2kjsp8vw4d0m25fb7; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email);


--
-- Name: users uk_d6m2soe014tvso1vopbp4m1vf; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_d6m2soe014tvso1vopbp4m1vf UNIQUE (bvn);


--
-- Name: users uk_du5v5sr43g5bfnji4vb8hg5s3; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_du5v5sr43g5bfnji4vb8hg5s3 UNIQUE (phone);


--
-- Name: leaderboard_entries ukhgrnik49qgulopon3vjx7wb0; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboard_entries
    ADD CONSTRAINT ukhgrnik49qgulopon3vjx7wb0 UNIQUE (leaderboard_id, user_id);


--
-- Name: user_badges user_badges_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_badges
    ADD CONSTRAINT user_badges_pkey PRIMARY KEY (id);


--
-- Name: user_goals user_goals_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_goals
    ADD CONSTRAINT user_goals_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: wallets wallets_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.wallets
    ADD CONSTRAINT wallets_pkey PRIMARY KEY (id);


--
-- Name: leaderboard_entries fk1xp9a2rpkpolc9rfh9sndp0d3; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboard_entries
    ADD CONSTRAINT fk1xp9a2rpkpolc9rfh9sndp0d3 FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: analytics_logs fk2mmvltp1agtgcw9xfgvhkq13q; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.analytics_logs
    ADD CONSTRAINT fk2mmvltp1agtgcw9xfgvhkq13q FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: user_goals fkbqj3pc51g999b3nsxgqm3fyj9; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_goals
    ADD CONSTRAINT fkbqj3pc51g999b3nsxgqm3fyj9 FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: wallets fkc1foyisidw7wqqrkamafuwn4e; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.wallets
    ADD CONSTRAINT fkc1foyisidw7wqqrkamafuwn4e FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: envelopes fkd69ckd02n22twge9n8mo3cbu; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.envelopes
    ADD CONSTRAINT fkd69ckd02n22twge9n8mo3cbu FOREIGN KEY (budget_id) REFERENCES public.budgets(id);


--
-- Name: leaderboard_entries fkevaohs4nwnvdymfl0f8kje2o6; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.leaderboard_entries
    ADD CONSTRAINT fkevaohs4nwnvdymfl0f8kje2o6 FOREIGN KEY (leaderboard_id) REFERENCES public.leaderboards(id);


--
-- Name: user_goals fki7clpcndtklwj0xuec166nuh7; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_goals
    ADD CONSTRAINT fki7clpcndtklwj0xuec166nuh7 FOREIGN KEY (envelope_id) REFERENCES public.envelopes(id);


--
-- Name: user_badges fkk6e00pguaij0uke6xr81gt045; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_badges
    ADD CONSTRAINT fkk6e00pguaij0uke6xr81gt045 FOREIGN KEY (badge_id) REFERENCES public.badges(id);


--
-- Name: budgets fkln0tm5tgf3f9q3sp9sa5m8m7b; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.budgets
    ADD CONSTRAINT fkln0tm5tgf3f9q3sp9sa5m8m7b FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: user_badges fkr46ah81sjymsn035m4ojstn5s; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.user_badges
    ADD CONSTRAINT fkr46ah81sjymsn035m4ojstn5s FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: otps otps_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.otps
    ADD CONSTRAINT otps_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: revenue_logs revenue_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.revenue_logs
    ADD CONSTRAINT revenue_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: transaction_logs transaction_logs_budget_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transaction_logs
    ADD CONSTRAINT transaction_logs_budget_id_fkey FOREIGN KEY (budget_id) REFERENCES public.budgets(id);


--
-- Name: transaction_logs transaction_logs_source_envelope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transaction_logs
    ADD CONSTRAINT transaction_logs_source_envelope_id_fkey FOREIGN KEY (source_envelope_id) REFERENCES public.envelopes(id);


--
-- Name: transaction_logs transaction_logs_target_envelope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transaction_logs
    ADD CONSTRAINT transaction_logs_target_envelope_id_fkey FOREIGN KEY (target_envelope_id) REFERENCES public.envelopes(id);


--
-- Name: transaction_logs transaction_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.transaction_logs
    ADD CONSTRAINT transaction_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--

